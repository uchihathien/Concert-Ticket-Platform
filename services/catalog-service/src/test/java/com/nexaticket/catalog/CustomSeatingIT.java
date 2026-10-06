// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.support.CatalogTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sơ đồ tự dựng của ban tổ chức: ba khu VIP / Thường / Khác với số ghế tự chọn.
 *
 * <p>Trọng tâm không phải "khai được không" mà là <b>sửa lại thì mất gì</b>. Đó là chỗ một API thay
 * cả tập dễ làm hỏng dữ liệu nhất: đổi số ghế của một khu mà làm bay mức giá đã khai cho nó là một
 * lỗi người dùng chỉ phát hiện ở bước publish.
 */
class CustomSeatingIT extends CatalogTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("khai ba khu trong một lần: VIP 100, Thường 500, khu đứng 300")
    void khai_ba_khu_mot_lan() throws Exception {
        UUID venueId = createVenue();

        JsonNode result =
                json.readTree(putZones(venueId, threeZones()).getResponse().getContentAsString());

        assertThat(result.path("inserted").asInt()).isEqualTo(3);
        assertThat(result.path("venue").path("capacity").asInt()).isEqualTo(900);
        assertThat(seatCountOf(result, "VIP")).isEqualTo(100);
        assertThat(seatCountOf(result, "THUONG")).isEqualTo(500);
        assertThat(seatCountOf(result, "KHAC")).isEqualTo(300);
    }

    @Test
    @DisplayName("đổi số ghế của một khu thì giữ nguyên hạng vé đã khai cho khu đó")
    void doi_so_ghe_khong_lam_mat_gia() throws Exception {
        // Đây là lý do repository upsert theo zone_code thay vì xoá-rồi-chèn. Xoá sạch nghĩa là
        // "VIP: 100 → 150 ghế" cũng làm bay mức giá VIP, và ban tổ chức phải nhập lại thứ họ không
        // hề đụng tới.
        UUID venueId = createVenue();
        putZones(venueId, threeZones());
        Fixture f = draftWithTicketType(venueId, "VIP", 2_000_000);

        JsonNode result = json.readTree(putZones(
                        venueId,
                        List.of(
                                zone("VIP", "Khu VIP", 15, 10),
                                zone("THUONG", "Khu Thường", 20, 25),
                                standing("KHAC", 300)))
                .getResponse()
                .getContentAsString());

        assertThat(result.path("removedTicketTypes").asInt()).isZero();
        assertThat(seatCountOf(result, "VIP")).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT price_vnd FROM ticket_types WHERE id = ?", Long.class, f.ticketTypeId()))
                .isEqualTo(2_000_000L);
    }

    @Test
    @DisplayName("bỏ hẳn một khu thì hạng vé của khu đó bị xoá theo, và phản hồi nói rõ")
    void bo_khu_thi_bao_so_hang_ve_mat_theo() throws Exception {
        UUID venueId = createVenue();
        putZones(venueId, threeZones());
        draftWithTicketType(venueId, "KHAC", 300_000);

        JsonNode result = json.readTree(
                putZones(venueId, List.of(zone("VIP", "Khu VIP", 10, 10), zone("THUONG", "Khu Thường", 20, 25)))
                        .getResponse()
                        .getContentAsString());

        assertThat(result.path("removedZones").asInt()).isEqualTo(1);
        assertThat(result.path("removedTicketTypes").asInt()).isEqualTo(1);
        assertThat(result.path("venue").path("zones")).hasSize(2);
    }

    @Test
    @DisplayName("sơ đồ đến từ khung của nền tảng thì không sửa được")
    void so_do_tu_khung_bi_khoa() throws Exception {
        // "Khu vực cố định, không thay đổi được" — kiểm bằng một request thật, không bằng một dòng
        // trong tài liệu.
        UUID venueId = venueFromTemplate();

        MvcResult result = putZones(venueId, threeZones());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("VENUE_LAYOUT_LOCKED");
    }

    @Test
    @DisplayName("địa điểm đã có sự kiện từng lên bán thì không sửa sơ đồ được")
    void da_len_ban_thi_khong_sua_so_do() throws Exception {
        // Tồn kho bên Inventory dựng từ đúng những mã khu này. Đổi sơ đồ ở đây không đổi được
        // những chỗ đã sinh ra bên kia — kết quả là ghế mồ côi thuộc một khu không còn tồn tại.
        UUID venueId = createVenue();
        putZones(venueId, threeZones());
        Fixture f = draftWithTicketType(venueId, "VIP", 2_000_000);
        perform(post("/v1/organizations/" + ORG + "/events/" + f.eventId() + "/publish"));

        MvcResult result = putZones(venueId, List.of(zone("VIP", "Khu VIP", 15, 10)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("VENUE_IN_USE");

        // Rút xuống KHÔNG mở khoá: tồn kho và vé đã bán vẫn còn nguyên bên Inventory.
        perform(post("/v1/organizations/" + ORG + "/events/" + f.eventId() + "/unpublish"));
        assertThat(putZones(venueId, List.of(zone("VIP", "Khu VIP", 15, 10)))
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("sơ đồ rỗng bị từ chối ngay, không để lộ ra ở bước publish")
    void so_do_rong_bi_tu_choi() throws Exception {
        UUID venueId = createVenue();

        MvcResult result = putZones(venueId, List.of());

        // 400 của bean validation (@NotEmpty), không phải 409: request sai hình dạng, không phải
        // đối tượng sai trạng thái.
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("địa điểm của tổ chức khác: 404, không sửa xuyên tổ chức")
    void khong_sua_duoc_xuyen_to_chuc() throws Exception {
        UUID venueId = createVenue();

        MvcResult result = mockMvc.perform(put("/v1/organizations/" + OTHER_ORG + "/venues/" + venueId + "/zones")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("zones", threeZones()))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private record Fixture(UUID eventId, UUID sessionId, UUID ticketTypeId) {}

    private UUID createVenue() throws Exception {
        MvcResult result = perform(post("/v1/organizations/" + ORG + "/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Sân khấu tự dựng", "city", "Đà Nẵng"))));
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    /** Địa điểm sinh từ khung của nền tảng — sơ đồ của nó là kết cấu cố định. */
    private UUID venueFromTemplate() throws Exception {
        actAsSuperAdmin();
        UUID templateId = UUID.fromString(json.readTree(perform(post("/v1/platform/concert-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(
                                        Map.of("code", "ARENA_5K", "name", "Nhà thi đấu", "category", "concert"))))
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());
        perform(put("/v1/platform/concert-templates/" + templateId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("zones", ConcertTemplateIT.threeZones()))));
        perform(post("/v1/platform/concert-templates/" + templateId + "/activate"));
        actAsOrganizer();

        Instant startsAt = Instant.now().plus(Duration.ofDays(30));
        JsonNode event = json.readTree(perform(post("/v1/organizations/" + ORG + "/events/from-template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "templateId",
                                templateId,
                                "title",
                                "Sự kiện từ khung",
                                "venueName",
                                "Nhà thi đấu Phú Thọ",
                                "city",
                                "TP.HCM",
                                "startsAt",
                                startsAt.toString(),
                                "salesOpenAt",
                                Instant.now().toString(),
                                "salesCloseAt",
                                startsAt.toString()))))
                .getResponse()
                .getContentAsString());
        return UUID.fromString(event.path("venue").path("id").asText());
    }

    /** Một sự kiện nháp trên địa điểm này, có đúng một hạng vé trỏ vào khu {@code zoneCode}. */
    private Fixture draftWithTicketType(UUID venueId, String zoneCode, long priceVnd) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode event = json.readTree(perform(post("/v1/organizations/" + ORG + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "venueId",
                                venueId,
                                "title",
                                "Sự kiện " + suffix,
                                "slug",
                                "su-kien-" + suffix,
                                "category",
                                "nhac-song"))))
                .getResponse()
                .getContentAsString());
        UUID eventId = UUID.fromString(event.path("id").asText());
        UUID zoneId = UUID.fromString(
                zoneNode(event.path("venue").path("zones"), zoneCode).path("id").asText());

        Instant startsAt = Instant.now().plus(Duration.ofDays(30));
        JsonNode withSession =
                json.readTree(perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "startsAt", startsAt.toString(),
                                        "salesOpenAt", Instant.now().toString(),
                                        "salesCloseAt", startsAt.toString()))))
                        .getResponse()
                        .getContentAsString());
        UUID sessionId =
                UUID.fromString(withSession.path("sessions").get(0).path("id").asText());

        JsonNode priced = json.readTree(perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions/"
                                + sessionId + "/ticket-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("venueZoneId", zoneId, "name", "Hạng " + zoneCode, "priceVnd", priceVnd))))
                .getResponse()
                .getContentAsString());
        UUID typeId = UUID.fromString(priced.path("sessions")
                .get(0)
                .path("ticketTypes")
                .get(0)
                .path("id")
                .asText());
        return new Fixture(eventId, sessionId, typeId);
    }

    private MvcResult putZones(UUID venueId, List<Map<String, Object>> zones) throws Exception {
        return perform(put("/v1/organizations/" + ORG + "/venues/" + venueId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("zones", zones))));
    }

    /** VIP 100 ghế, Thường 500 ghế, khu đứng 300 chỗ. */
    private static List<Map<String, Object>> threeZones() {
        return List.of(zone("VIP", "Khu VIP", 10, 10), zone("THUONG", "Khu Thường", 20, 25), standing("KHAC", 300));
    }

    private static Map<String, Object> zone(String code, String name, int rows, int seatsPerRow) {
        return Map.of("zoneCode", code, "name", name, "kind", "SEATED", "rowCount", rows, "seatsPerRow", seatsPerRow);
    }

    private static Map<String, Object> standing(String code, int capacity) {
        return Map.of("zoneCode", code, "name", "Khu " + code, "kind", "STANDING", "capacity", capacity);
    }

    private static int seatCountOf(JsonNode response, String zoneCode) {
        return zoneNode(response.path("venue").path("zones"), zoneCode)
                .path("seatCount")
                .asInt();
    }

    private static JsonNode zoneNode(JsonNode zones, String zoneCode) {
        for (JsonNode zone : zones) {
            if (zoneCode.equals(zone.path("zoneCode").asText())) {
                return zone;
            }
        }
        throw new AssertionError("Không thấy khu " + zoneCode);
    }

    private String codeOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .path("code")
                .asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER)).andReturn();
    }
}
