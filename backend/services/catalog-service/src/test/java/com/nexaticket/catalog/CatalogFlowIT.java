// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.support.CatalogTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cả đường đi của ban tổ chức, qua HTTP thật: địa điểm → khu → sự kiện → suất → giá → publish →
 * khách nhìn thấy.
 *
 * <p>Test này tồn tại vì từng bước riêng lẻ đúng vẫn không bảo đảm cả chuỗi đúng. Ba thứ chỉ hỏng ở
 * mức chuỗi: thứ tự filter (mọi request đã đăng nhập trả 401), cách ly giữa các tổ chức (thấy được
 * dữ liệu của người khác), và ranh giới nháp/đã bán (khách thấy thứ chưa công bố).
 */
class CatalogFlowIT extends CatalogTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("dựng sự kiện, publish, khách thấy — và outbox có message cho Inventory")
    void ca_duong_di_cua_ban_to_chuc() throws Exception {
        UUID venueId = createVenue("Nhà hát Lớn", "Hà Nội");
        createZone(venueId, "A", "Khu A", 10, 20);

        UUID eventId = createEvent(venueId, "Đêm nhạc thử", "dem-nhac-thu");
        JsonNode withSession = createSession(eventId);
        UUID sessionId =
                UUID.fromString(withSession.path("sessions").get(0).path("id").asText());

        // Trước khi khai giá: đã có suất nhưng chưa bán được gì.
        assertThat(blockersOf(withSession)).containsExactly("SESSION_WITHOUT_TICKET_TYPE");

        UUID zoneId = UUID.fromString(
                withSession.path("venue").path("zones").get(0).path("id").asText());
        JsonNode priced = createTicketType(eventId, sessionId, zoneId, "Hạng A", 500_000);
        assertThat(blockersOf(priced)).isEmpty();

        // Chưa publish thì khách chưa thấy.
        mockMvc.perform(get("/v1/events/dem-nhac-thu"))
                .andExpect(res -> assertThat(res.getResponse().getStatus())
                        .as("sự kiện nháp phải là 404 với khách, không phải 200")
                        .isEqualTo(404));

        JsonNode published = publish(eventId);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");

        // Khách thấy, và thấy đủ giá.
        MvcResult detail = mockMvc.perform(get("/v1/events/dem-nhac-thu"))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(200))
                .andReturn();
        JsonNode body = json.readTree(detail.getResponse().getContentAsString());
        assertThat(body.path("title").asText()).isEqualTo("Đêm nhạc thử");
        assertThat(body.path("sessions")
                        .get(0)
                        .path("tiers")
                        .get(0)
                        .path("priceVnd")
                        .asLong())
                .isEqualTo(500_000);

        // Và Inventory đã có việc để làm: đúng một message, mang đúng số ghế của khu 10×20.
        List<String> payloads = jdbc.queryForList(
                "SELECT payload::text FROM outbox WHERE event_type = 'session.published'", String.class);
        assertThat(payloads).hasSize(1);
        JsonNode manifest = json.readTree(payloads.get(0));
        assertThat(manifest.path("eventSessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(manifest.path("seats")).hasSize(200);
    }

    @Test
    @DisplayName("không phải thành viên: 404 chứ không phải 403")
    void to_chuc_khac_thi_khong_thay() throws Exception {
        // 404 chứ không phải 403 là có chủ đích: 403 xác nhận rằng tài nguyên của tổ chức kia tồn
        // tại. Với một nền tảng nhiều ban tổ chức cạnh tranh nhau, đó đã là rò rỉ thông tin.
        mockMvc.perform(get("/v1/organizations/" + OTHER_ORG + "/events").header("Authorization", BEARER))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    @DisplayName("không có token: 401, và không lộ dữ liệu")
    void khong_co_token_thi_401() throws Exception {
        mockMvc.perform(get("/v1/organizations/" + ORG + "/events"))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("danh sách công khai không cần đăng nhập")
    void danh_sach_cong_khai_mo() throws Exception {
        mockMvc.perform(get("/v1/events"))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(200));
    }

    @Test
    @DisplayName("publish khi chưa đủ điều kiện: 409 kèm danh sách vướng mắc")
    void publish_som_thi_bao_ro_thieu_gi() throws Exception {
        UUID venueId = createVenue("Địa điểm chưa khai khu", "Hà Nội");
        UUID eventId = createEvent(venueId, "Sự kiện dở dang", "su-kien-do-dang");

        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/publish")
                        .header("Authorization", BEARER))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = json.readTree(result.getResponse().getContentAsString());
        assertThat(error.path("code").asText()).isEqualTo("PUBLISH_BLOCKED");
        // Danh sách này là thứ màn hình quản trị vẽ thành checklist đỏ.
        assertThat(error.path("meta").path("blockers").toString())
                .contains("VENUE_WITHOUT_ZONE")
                .contains("NO_SESSION");
    }

    @Test
    @DisplayName("khai giá hai lần cho cùng một khu: 409, không phải 500")
    void khai_gia_trung_khu() throws Exception {
        UUID venueId = createVenue("Nhà hát", "Hà Nội");
        createZone(venueId, "A", "Khu A", 5, 5);
        UUID eventId = createEvent(venueId, "Sự kiện", "su-kien-trung-khu");
        JsonNode withSession = createSession(eventId);
        UUID sessionId =
                UUID.fromString(withSession.path("sessions").get(0).path("id").asText());
        UUID zoneId = UUID.fromString(
                withSession.path("venue").path("zones").get(0).path("id").asText());

        createTicketType(eventId, sessionId, zoneId, "Hạng A", 500_000);

        MvcResult second = mockMvc.perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions/"
                                + sessionId + "/ticket-types")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "venueZoneId", zoneId, "name", "Hạng A lần hai", "priceVnd", 600_000))))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(second.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("ZONE_ALREADY_PRICED");
    }

    @Test
    @DisplayName("rút xuống: khách không thấy nữa, nhưng dữ liệu còn nguyên")
    void rut_xuong_khong_xoa_gi() throws Exception {
        UUID venueId = createVenue("Nhà hát", "Hà Nội");
        createZone(venueId, "A", "Khu A", 5, 5);
        UUID eventId = createEvent(venueId, "Sự kiện rút", "su-kien-rut");
        JsonNode withSession = createSession(eventId);
        UUID sessionId =
                UUID.fromString(withSession.path("sessions").get(0).path("id").asText());
        UUID zoneId = UUID.fromString(
                withSession.path("venue").path("zones").get(0).path("id").asText());
        createTicketType(eventId, sessionId, zoneId, "Hạng A", 500_000);
        publish(eventId);

        mockMvc.perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/unpublish")
                        .header("Authorization", BEARER))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(200));

        mockMvc.perform(get("/v1/events/su-kien-rut"))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(404));

        // Suất diễn và hạng vé vẫn còn: khách đã mua vé phải vào được, nên rút xuống không được
        // xoá gì cả.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ticket_types WHERE event_session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);
    }

    // --- gọi API -----------------------------------------------------------

    private UUID createVenue(String name, String city) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/venues")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", name, "city", city))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private void createZone(UUID venueId, String code, String name, int rows, int seatsPerRow) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/venues/" + venueId + "/zones")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "zoneCode", code,
                                "name", name,
                                "kind", "SEATED",
                                "rowCount", rows,
                                "seatsPerRow", seatsPerRow))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private UUID createEvent(UUID venueId, String title, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/events")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "venueId", venueId, "title", title, "slug", slug, "category", "nhac-song"))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private JsonNode createSession(UUID eventId) throws Exception {
        Instant startsAt = Instant.now().plus(Duration.ofDays(30));
        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "startsAt", startsAt.toString(),
                                "salesOpenAt", Instant.now().toString(),
                                "salesCloseAt", startsAt.toString()))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createTicketType(UUID eventId, UUID sessionId, UUID zoneId, String name, long priceVnd)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions/"
                                + sessionId + "/ticket-types")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("venueZoneId", zoneId, "name", name, "priceVnd", priceVnd))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode publish(UUID eventId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/publish")
                        .header("Authorization", BEARER))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> blockersOf(JsonNode detail) {
        List<String> names = new java.util.ArrayList<>();
        detail.path("blockers").forEach(node -> names.add(node.asText()));
        return names;
    }
}
