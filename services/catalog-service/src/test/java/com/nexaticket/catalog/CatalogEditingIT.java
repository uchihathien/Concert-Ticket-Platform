// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.support.CatalogTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Sửa và xoá: những thao tác mà ban tổ chức chắc chắn sẽ cần và chỉ nhận ra khi đã gõ nhầm.
 *
 * <p>Trọng tâm không phải "sửa được không" mà là <b>ranh giới</b>: sửa được lúc nào, và id của
 * người khác có sửa được từ đây không.
 */
class CatalogEditingIT extends CatalogTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("sửa giá hạng vé khi còn nháp")
    void sua_gia_khi_con_nhap() throws Exception {
        Fixture f = draftWithPrice(500_000);

        JsonNode after = json.readTree(perform(patch(typeUrl(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("priceVnd", 750_000))))
                .getResponse()
                .getContentAsString());

        assertThat(after.path("sessions")
                        .get(0)
                        .path("ticketTypes")
                        .get(0)
                        .path("priceVnd")
                        .asLong())
                .isEqualTo(750_000);
    }

    @Test
    @DisplayName("xoá hạng vé cuối cùng thì sự kiện quay lại trạng thái chưa bán được")
    void xoa_hang_ve_cuoi_thi_chan_publish() throws Exception {
        // Không phải chi tiết vụn vặt: nếu checklist không cập nhật lại, ban tổ chức sẽ bấm Publish
        // và nhận 409 mà không hiểu vì sao — đúng thứ mà checklist sinh ra để tránh.
        Fixture f = draftWithPrice(500_000);

        JsonNode after = json.readTree(perform(delete(typeUrl(f))).getResponse().getContentAsString());

        assertThat(after.path("blockers").toString()).contains("SESSION_WITHOUT_TICKET_TYPE");
    }

    @Test
    @DisplayName("đang bán thì không sửa được giá — phải rút xuống trước")
    void dang_ban_thi_khong_sua_duoc() throws Exception {
        // Tồn kho bên Inventory được dựng một lần lúc publish và mang theo giá đã chốt. Cho sửa ở
        // đây sẽ tạo ra hai mức giá cho cùng một chỗ: giá khách nhìn thấy và giá khách bị tính.
        Fixture f = draftWithPrice(500_000);
        perform(post(eventUrl(f) + "/publish"));

        MvcResult blocked = perform(patch(typeUrl(f))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("priceVnd", 1_000_000))));

        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(blocked.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("INVALID_EVENT_STATE");
    }

    @Test
    @DisplayName("rút xuống rồi thì sửa được, publish lại được")
    void rut_xuong_roi_sua_duoc() throws Exception {
        Fixture f = draftWithPrice(500_000);
        perform(post(eventUrl(f) + "/publish"));
        perform(post(eventUrl(f) + "/unpublish"));

        perform(patch(typeUrl(f))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("priceVnd", 900_000))));

        JsonNode republished = json.readTree(
                perform(post(eventUrl(f) + "/publish")).getResponse().getContentAsString());
        assertThat(republished.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(republished
                        .path("sessions")
                        .get(0)
                        .path("ticketTypes")
                        .get(0)
                        .path("priceVnd")
                        .asLong())
                .isEqualTo(900_000);
    }

    @Test
    @DisplayName("hạng vé của sự kiện khác: 404, không sửa được xuyên sự kiện")
    void khong_sua_duoc_xuyen_su_kien() throws Exception {
        Fixture a = draftWithPrice(500_000);
        Fixture b = draftWithPrice(300_000);

        // Đường dẫn của sự kiện A, nhưng id hạng vé của sự kiện B. Nếu handler chỉ tra hạng vé theo
        // id mà không kiểm nó có thuộc sự kiện trong đường dẫn hay không, thao tác này sẽ thành
        // công — và bộ lọc theo tổ chức ở tầng trên trở nên vô nghĩa.
        MvcResult result = perform(patch(eventUrl(a) + "/sessions/" + a.sessionId() + "/ticket-types/" + b.typeId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("priceVnd", 1))));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("xoá bản nháp: xoá sạch cả suất diễn và hạng vé")
    void xoa_ban_nhap() throws Exception {
        Fixture f = draftWithPrice(500_000);

        assertThat(perform(delete(eventUrl(f))).getResponse().getStatus()).isEqualTo(204);
        assertThat(perform(get(eventUrl(f))).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM event_sessions WHERE event_id = ?", Integer.class, f.eventId()))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("sự kiện đã bán thì không xoá được — phải huỷ")
    void da_ban_thi_khong_xoa_duoc() throws Exception {
        // Xoá sẽ để lại tồn kho và vé mồ côi ở hai service khác, trỏ vào một sự kiện không còn tồn
        // tại. Huỷ giữ nguyên mọi thứ để còn hoàn tiền và đối soát được.
        Fixture f = draftWithPrice(500_000);
        perform(post(eventUrl(f) + "/publish"));

        assertThat(perform(delete(eventUrl(f))).getResponse().getStatus()).isEqualTo(409);

        JsonNode cancelled = json.readTree(
                perform(post(eventUrl(f) + "/cancel")).getResponse().getContentAsString());
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("huỷ rồi thì khách không thấy nữa, và không huỷ lại được")
    void huy_la_trang_thai_cuoi() throws Exception {
        Fixture f = draftWithPrice(500_000);
        perform(post(eventUrl(f) + "/publish"));
        perform(post(eventUrl(f) + "/cancel"));

        assertThat(perform(get("/v1/events/" + f.slug())).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(perform(post(eventUrl(f) + "/cancel")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("xoá suất diễn kéo theo hạng vé của nó")
    void xoa_suat_keo_theo_hang_ve() throws Exception {
        Fixture f = draftWithPrice(500_000);

        perform(delete(eventUrl(f) + "/sessions/" + f.sessionId()));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ticket_types WHERE event_session_id = ?", Integer.class, f.sessionId()))
                .isEqualTo(0);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private record Fixture(UUID eventId, UUID sessionId, UUID typeId, String slug) {}

    private String eventUrl(Fixture f) {
        return "/v1/organizations/" + ORG + "/events/" + f.eventId();
    }

    private String typeUrl(Fixture f) {
        return eventUrl(f) + "/sessions/" + f.sessionId() + "/ticket-types/" + f.typeId();
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER)).andReturn();
    }

    /** Một sự kiện nháp đã đủ điều kiện publish: địa điểm có khu, một suất, một hạng vé. */
    private Fixture draftWithPrice(long priceVnd) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UUID venueId = UUID.fromString(json.readTree(perform(post("/v1/organizations/" + ORG + "/venues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(Map.of("name", "Nhà hát " + suffix, "city", "Hà Nội"))))
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());

        perform(post("/v1/organizations/" + ORG + "/venues/" + venueId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("zoneCode", "A", "name", "Khu A", "kind", "SEATED", "rowCount", 3, "seatsPerRow", 4))));

        String slug = "su-kien-" + suffix;
        JsonNode event = json.readTree(perform(post("/v1/organizations/" + ORG + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "venueId",
                                venueId,
                                "title",
                                "Sự kiện " + suffix,
                                "slug",
                                slug,
                                "category",
                                "nhac-song"))))
                .getResponse()
                .getContentAsString());
        UUID eventId = UUID.fromString(event.path("id").asText());
        UUID zoneId = UUID.fromString(
                event.path("venue").path("zones").get(0).path("id").asText());

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
                                Map.of("venueZoneId", zoneId, "name", "Hạng A", "priceVnd", priceVnd))))
                .getResponse()
                .getContentAsString());
        UUID typeId = UUID.fromString(priced.path("sessions")
                .get(0)
                .path("ticketTypes")
                .get(0)
                .path("id")
                .asText());

        return new Fixture(eventId, sessionId, typeId, slug);
    }
}
