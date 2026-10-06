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
import java.util.HashMap;
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
 * Tổ chức chọn khung rồi điền thông tin — luồng chính của cả tính năng.
 *
 * <p>Một lời gọi phải thay được bốn màn hình, nên phép thử là: sau lời gọi đó, sự kiện có publish
 * được ngay không. Nếu còn thiếu bất cứ thứ gì thì "dựng nhanh" chỉ là dời công việc sang chỗ khác.
 */
class EventFromTemplateIT extends CatalogTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("chọn khung + điền thông tin: ra một sự kiện publish được ngay")
    void ap_khung_ra_su_kien_hoan_chinh() throws Exception {
        UUID templateId = activeTemplate();

        JsonNode event =
                json.readTree(createFromTemplate(templateId, null).getResponse().getContentAsString());

        // Đủ cả bốn thứ mà bốn màn hình cũ dựng ra: địa điểm có khu, sự kiện, suất diễn, hạng vé.
        assertThat(event.path("status").asText()).isEqualTo("DRAFT");
        assertThat(event.path("venue").path("zones")).hasSize(3);
        assertThat(event.path("venue").path("capacity").asInt()).isEqualTo(1_600);
        assertThat(event.path("sessions")).hasSize(1);
        assertThat(event.path("sessions").get(0).path("ticketTypes")).hasSize(3);

        // Và đây là phép thử thật: checklist publish không còn vướng mắc nào.
        assertThat(event.path("blockers")).isEmpty();

        UUID eventId = UUID.fromString(event.path("id").asText());
        JsonNode published = json.readTree(perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/publish"))
                .getResponse()
                .getContentAsString());
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("khu và số ghế chép nguyên từ khung, không phải nhập lại")
    void khu_chep_nguyen_tu_khung() throws Exception {
        UUID templateId = activeTemplate();

        JsonNode event =
                json.readTree(createFromTemplate(templateId, null).getResponse().getContentAsString());

        JsonNode vip = zoneByCode(event, "VIP");
        assertThat(vip.path("rowCount").asInt()).isEqualTo(10);
        assertThat(vip.path("seatsPerRow").asInt()).isEqualTo(10);
        assertThat(vip.path("seatCount").asInt()).isEqualTo(100);

        JsonNode san = zoneByCode(event, "SAN");
        assertThat(san.path("kind").asText()).isEqualTo("STANDING");
        assertThat(san.path("seatCount").asInt()).isEqualTo(1_000);

        // Dấu vết: khu đến từ khung nào. Đây là thứ khoá sơ đồ lại — xem CustomSeatingIT.
        UUID venueId = UUID.fromString(event.path("venue").path("id").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM venue_zones WHERE venue_id = ? AND source_template_zone_id IS NOT NULL",
                        Integer.class,
                        venueId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("giá của tổ chức thắng giá gợi ý của khung")
    void gia_cua_to_chuc_thang() throws Exception {
        // Khu vực là kết cấu (nền tảng quyết định), giá là quyết định thương mại của tổ chức
        // (ADR-1010). Khung chỉ gợi ý.
        UUID templateId = activeTemplate();

        JsonNode event = json.readTree(createFromTemplate(templateId, Map.of("VIP", 3_000_000L))
                .getResponse()
                .getContentAsString());

        assertThat(ticketTypePrice(event, "VIP")).isEqualTo(3_000_000);
        // Khu không khai giá thì lấy gợi ý của khung, không rơi về 0.
        assertThat(ticketTypePrice(event, "THUONG")).isEqualTo(800_000);
    }

    @Test
    @DisplayName("khung không gợi ý giá mà tổ chức cũng không khai: 422 kèm danh sách khu còn thiếu")
    void thieu_gia_thi_tu_choi() throws Exception {
        // Không rơi về 0: giá 0 là "vé mời", một quyết định thương mại có thật. Lấy nó làm mặc định
        // sẽ biến một ô nhập bị bỏ quên thành một khu bán miễn phí.
        actAsSuperAdmin();
        UUID templateId = createTemplate("KHONG_GIA");
        putZones(
                templateId,
                List.of(Map.of(
                        "zoneCode", "VIP", "name", "Khu VIP", "kind", "SEATED", "rowCount", 5, "seatsPerRow", 5)));
        perform(post("/v1/platform/concert-templates/" + templateId + "/activate"));
        actAsOrganizer();

        MvcResult result = createFromTemplate(templateId, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("ZONE_PRICE_REQUIRED");
        // Danh sách mã khu còn thiếu đi kèm để form điền được ngay chỗ thiếu thay vì phải dò.
        assertThat(body.toString()).contains("VIP");

        // Và không để lại địa điểm mồ côi: cả lời gọi nằm trong một transaction.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("khung đã lưu trữ thì không dựng sự kiện mới được")
    void khung_luu_tru_khong_dung_duoc() throws Exception {
        UUID templateId = activeTemplate();
        actAsSuperAdmin();
        perform(post("/v1/platform/concert-templates/" + templateId + "/archive"));
        actAsOrganizer();

        MvcResult result = createFromTemplate(templateId, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("TEMPLATE_NOT_USABLE");
    }

    @Test
    @DisplayName("không dựng được sự kiện cho tổ chức mình không thuộc")
    void khong_dung_duoc_cho_to_chuc_khac() throws Exception {
        UUID templateId = activeTemplate();

        MvcResult result = mockMvc.perform(post("/v1/organizations/" + OTHER_ORG + "/events/from-template")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(requestBody(templateId, null))))
                .andReturn();

        // 404 chứ không 403: TenantFilter không xác nhận tổ chức của người khác có tồn tại hay không.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    /** Một khung ACTIVE ba khu; trả về ở tư cách người dùng thường, không phải superadmin. */
    private UUID activeTemplate() throws Exception {
        actAsSuperAdmin();
        UUID templateId = createTemplate("ARENA_5K");
        putZones(templateId, ConcertTemplateIT.threeZones());
        perform(post("/v1/platform/concert-templates/" + templateId + "/activate"));
        actAsOrganizer();
        return templateId;
    }

    private UUID createTemplate(String code) throws Exception {
        MvcResult result = perform(post("/v1/platform/concert-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("code", code, "name", "Nhà thi đấu " + code, "category", "concert"))));
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private void putZones(UUID templateId, List<Map<String, Object>> zones) throws Exception {
        perform(put("/v1/platform/concert-templates/" + templateId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("zones", zones))));
    }

    private MvcResult createFromTemplate(UUID templateId, Map<String, Long> zonePrices) throws Exception {
        return perform(post("/v1/organizations/" + ORG + "/events/from-template")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(requestBody(templateId, zonePrices))));
    }

    private static Map<String, Object> requestBody(UUID templateId, Map<String, Long> zonePrices) {
        Instant startsAt = Instant.now().plus(Duration.ofDays(30));
        Map<String, Object> body = new HashMap<>();
        body.put("templateId", templateId);
        body.put("title", "Đêm nhạc Mùa Thu " + UUID.randomUUID().toString().substring(0, 8));
        body.put("venueName", "Nhà thi đấu Phú Thọ");
        body.put("city", "TP.HCM");
        body.put("startsAt", startsAt.toString());
        body.put("salesOpenAt", Instant.now().toString());
        body.put("salesCloseAt", startsAt.toString());
        if (zonePrices != null) {
            body.put("zonePrices", zonePrices);
        }
        return body;
    }

    private static JsonNode zoneByCode(JsonNode event, String zoneCode) {
        for (JsonNode zone : event.path("venue").path("zones")) {
            if (zoneCode.equals(zone.path("zoneCode").asText())) {
                return zone;
            }
        }
        throw new AssertionError("Không thấy khu " + zoneCode);
    }

    private static long ticketTypePrice(JsonNode event, String zoneCode) {
        for (JsonNode type : event.path("sessions").get(0).path("ticketTypes")) {
            if (zoneCode.equals(type.path("zoneCode").asText())) {
                return type.path("priceVnd").asLong();
            }
        }
        throw new AssertionError("Không thấy hạng vé cho khu " + zoneCode);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER)).andReturn();
    }
}
