// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.support.CatalogTestBase;
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
 * Khung concert của Tổng công ty.
 *
 * <p>Trọng tâm là <b>ranh giới quyền</b>: ai khai được kết cấu khán phòng, và ai chỉ được dùng lại
 * nó. Đó là toàn bộ lý do khung tồn tại như một khái niệm riêng thay vì một địa điểm mẫu.
 */
class ConcertTemplateIT extends CatalogTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("ban tổ chức không tạo được khung — kể cả khi họ là EVENT_MANAGER")
    void to_chuc_khong_tao_duoc_khung() throws Exception {
        // Người dùng trong test là EVENT_MANAGER của ORG, tức là quản được sự kiện của họ. Kết cấu
        // khán phòng chuẩn thì không: nếu tổ chức tự khai được khung thì "khung của Tổng công ty"
        // chỉ còn là một cái tên.
        MvcResult result = perform(post("/v1/platform/concert-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("code", "ARENA_5K", "name", "Nhà thi đấu 5.000", "category", "concert"))));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("superadmin dựng khung: tạo -> khai khu -> mở cho tổ chức dùng")
    void vong_doi_khung() throws Exception {
        actAsSuperAdmin();
        UUID templateId = createTemplate("ARENA_5K", "Nhà thi đấu 5.000 chỗ");

        JsonNode withZones = json.readTree(perform(put("/v1/platform/concert-templates/" + templateId + "/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("zones", threeZones()))))
                .getResponse()
                .getContentAsString());

        // 100 ghế VIP + 500 ghế thường + 1.000 chỗ đứng.
        assertThat(withZones.path("capacity").asInt()).isEqualTo(1_600);
        assertThat(withZones.path("zones")).hasSize(3);
        assertThat(withZones.path("status").asText()).isEqualTo("DRAFT");

        JsonNode active = json.readTree(perform(post("/v1/platform/concert-templates/" + templateId + "/activate"))
                .getResponse()
                .getContentAsString());
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("khung chưa khai khu thì không mở được")
    void khung_rong_khong_mo_duoc() throws Exception {
        actAsSuperAdmin();
        UUID templateId = createTemplate("RONG", "Khung rỗng");

        MvcResult result = perform(post("/v1/platform/concert-templates/" + templateId + "/activate"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("TEMPLATE_WITHOUT_ZONE");
    }

    @Test
    @DisplayName("mã khung trùng: 409, không lặng lẽ tạo cái thứ hai")
    void ma_khung_trung() throws Exception {
        actAsSuperAdmin();
        createTemplate("ARENA_5K", "Nhà thi đấu 5.000 chỗ");

        MvcResult trung = perform(post("/v1/platform/concert-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("code", "ARENA_5K", "name", "Tên khác", "category", "concert"))));

        assertThat(trung.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(trung)).isEqualTo("TEMPLATE_CODE_TAKEN");
    }

    @Test
    @DisplayName("thay tập khu là thay cả tập: khu cũ biến mất khỏi khung")
    void thay_ca_tap_khu() throws Exception {
        actAsSuperAdmin();
        UUID templateId = createTemplate("ARENA_5K", "Nhà thi đấu");
        putZones(templateId, threeZones());

        JsonNode sau = json.readTree(perform(put("/v1/platform/concert-templates/" + templateId + "/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "zones",
                                List.of(Map.of(
                                        "zoneCode", "VIP",
                                        "name", "Khu VIP",
                                        "kind", "SEATED",
                                        "rowCount", 5,
                                        "seatsPerRow", 5))))))
                .getResponse()
                .getContentAsString());

        assertThat(sau.path("zones")).hasSize(1);
        assertThat(sau.path("capacity").asInt()).isEqualTo(25);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM concert_template_zones WHERE template_id = ?", Integer.class, templateId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("tổ chức chỉ thấy khung ACTIVE, không thấy khung nháp của nền tảng")
    void to_chuc_chi_thay_khung_active() throws Exception {
        actAsSuperAdmin();
        UUID nhap = createTemplate("NHAP", "Khung đang dựng");
        UUID moRa = createTemplate("MO_RA", "Khung đã mở");
        putZones(moRa, threeZones());
        perform(post("/v1/platform/concert-templates/" + moRa + "/activate"));

        // Cùng người dùng, nhưng đi qua đường của tổ chức: bộ lọc trạng thái nằm trong chính đường
        // đó chứ không phải một tham số mà controller có thể quên truyền.
        JsonNode danhSach = json.readTree(perform(get("/v1/organizations/" + ORG + "/concert-templates"))
                .getResponse()
                .getContentAsString());

        assertThat(danhSach).hasSize(1);
        assertThat(danhSach.get(0).path("id").asText()).isEqualTo(moRa.toString());

        // Và khung nháp trả 404 chứ không 403: với ban tổ chức thì nó là thứ không tồn tại, còn 403
        // sẽ xác nhận rằng nó có.
        assertThat(perform(get("/v1/organizations/" + ORG + "/concert-templates/" + nhap))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("khung đã có tổ chức dùng thì lưu trữ được, xoá thì không")
    void khung_dang_dung_thi_khong_xoa_duoc() throws Exception {
        actAsSuperAdmin();
        UUID templateId = createTemplate("ARENA_5K", "Nhà thi đấu");
        putZones(templateId, threeZones());
        perform(post("/v1/platform/concert-templates/" + templateId + "/activate"));

        // Giả lập việc một tổ chức đã dựng địa điểm từ khung này. Đi thẳng vào database thay vì gọi
        // API vì luồng áp khung có bộ test riêng của nó — ở đây chỉ cần cái dấu vết.
        jdbc.update(
                "INSERT INTO venues (id, organization_id, name, city, source_template_id) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                ORG,
                "Nhà thi đấu Phú Thọ",
                "TP.HCM",
                templateId);

        MvcResult xoa = perform(delete("/v1/platform/concert-templates/" + templateId));
        assertThat(xoa.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(xoa)).isEqualTo("TEMPLATE_IN_USE");

        JsonNode luuTru = json.readTree(perform(post("/v1/platform/concert-templates/" + templateId + "/archive"))
                .getResponse()
                .getContentAsString());
        assertThat(luuTru.path("status").asText()).isEqualTo("ARCHIVED");
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private UUID createTemplate(String code, String name) throws Exception {
        MvcResult result = perform(post("/v1/platform/concert-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code, "name", name, "category", "concert"))));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private void putZones(UUID templateId, List<Map<String, Object>> zones) throws Exception {
        perform(put("/v1/platform/concert-templates/" + templateId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("zones", zones))));
    }

    /** VIP 100 ghế, Thường 500 ghế, khu đứng 1.000 chỗ — đúng hình dạng đề bài mô tả. */
    static List<Map<String, Object>> threeZones() {
        return List.of(
                Map.of(
                        "zoneCode",
                        "VIP",
                        "name",
                        "Khu VIP",
                        "kind",
                        "SEATED",
                        "rowCount",
                        10,
                        "seatsPerRow",
                        10,
                        "suggestedPriceVnd",
                        2_000_000),
                Map.of(
                        "zoneCode",
                        "THUONG",
                        "name",
                        "Khu Thường",
                        "kind",
                        "SEATED",
                        "rowCount",
                        20,
                        "seatsPerRow",
                        25,
                        "suggestedPriceVnd",
                        800_000),
                Map.of(
                        "zoneCode", "SAN",
                        "name", "Khu đứng",
                        "kind", "STANDING",
                        "capacity", 1_000,
                        "suggestedPriceVnd", 500_000));
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
