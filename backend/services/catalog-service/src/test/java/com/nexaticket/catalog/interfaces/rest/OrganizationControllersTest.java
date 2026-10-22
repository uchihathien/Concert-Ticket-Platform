// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.command.ConfigureVenueZonesHandler;
import com.nexaticket.catalog.application.command.CreateEventFromTemplateHandler;
import com.nexaticket.catalog.application.media.PosterUploadUseCase;
import com.nexaticket.catalog.application.media.SeatMapImageUseCase;
import com.nexaticket.catalog.application.query.AdminCatalogQuery;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.CheckinSessionQuery;
import com.nexaticket.catalog.application.query.DashboardViews;
import com.nexaticket.catalog.application.query.FloorPlanQuery;
import com.nexaticket.catalog.application.query.OrganizationDashboardQuery;
import com.nexaticket.catalog.application.query.SessionDirectory;
import com.nexaticket.catalog.application.query.SessionDirectoryQuery;
import com.nexaticket.catalog.application.query.TemplateQueries;
import com.nexaticket.catalog.support.WebSliceBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Các controller còn lại của khu vực tổ chức: soát vé, danh bạ suất, bảng điều khiển, tải ảnh, sơ
 * đồ ghế. Hai cái đầu dùng {@code @RequiresPermission}, nên {@code PermissionGuard} thật chạy ở
 * đây — vai trò sai phải là 403 và không được chạm truy vấn.
 */
@WebMvcTest(
        controllers = {
            CheckinSessionController.class,
            OrganizationSessionDirectoryController.class,
            OrganizationDashboardController.class,
            OrganizationMediaController.class,
            OrganizationSeatingController.class
        })
class OrganizationControllersTest extends WebSliceBase {

    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID OTHER_ORG = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-a333-333333333333");
    private static final UUID VENUE = UUID.fromString("66666666-6666-4666-a666-666666666666");
    private static final String BASE = "/v1/organizations/" + ORG;

    @MockitoBean
    CheckinSessionQuery checkin;

    @MockitoBean
    SessionDirectoryQuery directory;

    @MockitoBean
    OrganizationDashboardQuery dashboard;

    @MockitoBean
    PosterUploadUseCase uploads;

    @MockitoBean
    CreateEventFromTemplateHandler fromTemplate;

    @MockitoBean
    ConfigureVenueZonesHandler configureZones;

    @MockitoBean
    TemplateQueries templateQueries;

    @MockitoBean
    AdminCatalogQuery adminQuery;

    @MockitoBean
    FloorPlanQuery floorPlans;

    @MockitoBean
    SeatMapImageUseCase seatMapImages;

    @MockitoBean
    CatalogQueries queries;

    private String as(Role role) {
        return bearer(tokenFor(UUID.randomUUID(), Map.of(TenantId.of(ORG), role), false));
    }

    private String manager;

    @BeforeEach
    void dangNhap() {
        manager = as(Role.EVENT_MANAGER);
    }

    @Nested
    @DisplayName("Danh sách suất cho soát vé")
    class SoatVe {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"CHECKIN_STAFF", "EVENT_MANAGER", "ORG_ADMIN", "ORG_OWNER"})
        @DisplayName("vai trò có quyền CHECKIN_SCAN: 200")
        void co_quyen(Role role) throws Exception {
            when(checkin.forOrganization(ORG))
                    .thenReturn(List.of(new CatalogViews.CheckinSession(
                            UUID.randomUUID(),
                            EVENT,
                            "Đêm nhạc",
                            "Nhà hát",
                            Instant.parse("2026-12-01T12:00:00Z"),
                            null)));

            mvc.perform(get(BASE + "/checkin-sessions").header("Authorization", as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventTitle").value("Đêm nhạc"));
        }

        @Test
        @DisplayName("không token: 401; tổ chức khác: 404; id tổ chức hỏng: 404 — không chạm truy vấn")
        void khong_duoc_phep() throws Exception {
            mvc.perform(get(BASE + "/checkin-sessions")).andExpect(status().isUnauthorized());
            mvc.perform(get("/v1/organizations/" + OTHER_ORG + "/checkin-sessions")
                            .header("Authorization", manager))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/v1/organizations/xyz/checkin-sessions").header("Authorization", manager))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(checkin);
        }

        @Test
        @DisplayName("khách thuộc tổ chức với vai trò CUSTOMER: 403 chứ không 200")
        void vai_tro_khach() throws Exception {
            mvc.perform(get(BASE + "/checkin-sessions").header("Authorization", as(Role.CUSTOMER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(checkin);
        }
    }

    @Nested
    @DisplayName("Danh bạ suất cho thống kê")
    class DanhBa {

        @Test
        @DisplayName("quản lý sự kiện: 200")
        void quan_ly() throws Exception {
            when(directory.forOrganization(ORG)).thenReturn(new SessionDirectory.Listing(List.of()));

            mvc.perform(get(BASE + "/stats/catalog/sessions").header("Authorization", manager))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions").isArray());
        }

        @Test
        @DisplayName("nhân viên soát vé: 403 — họ có danh sách riêng ở /checkin-sessions")
        void nhan_vien() throws Exception {
            mvc.perform(get(BASE + "/stats/catalog/sessions").header("Authorization", as(Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(directory);
        }

        @Test
        @DisplayName("tổ chức khác: 404")
        void to_chuc_khac() throws Exception {
            mvc.perform(get("/v1/organizations/" + OTHER_ORG + "/stats/catalog/sessions")
                            .header("Authorization", manager))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(directory);
        }
    }

    @Nested
    @DisplayName("Bảng điều khiển")
    class BangDieuKhien {

        @Test
        @DisplayName("tổng quan: 200; tổ chức khác: 404; không token: 401")
        void tong_quan() throws Exception {
            when(dashboard.overview(ORG))
                    .thenReturn(new DashboardViews.OrganizationDashboard(
                            ORG,
                            new DashboardViews.DashboardTotals(0, 0, 0, 0, 0, 0),
                            List.of(),
                            List.of(),
                            List.of("analytics")));

            mvc.perform(get(BASE + "/dashboard").header("Authorization", manager))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded[0]").value("analytics"));
            mvc.perform(get("/v1/organizations/" + OTHER_ORG + "/dashboard").header("Authorization", manager))
                    .andExpect(status().isNotFound());
            mvc.perform(get(BASE + "/dashboard")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("master data: sự kiện không có → 404; id sai → 400; không đủ quyền (handler) → 403")
        void master_data() throws Exception {
            UUID missing = UUID.randomUUID();
            when(dashboard.masterData(ORG, missing))
                    .thenThrow(new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
            when(dashboard.masterData(ORG, EVENT)).thenThrow(ApiException.forbidden("no"));

            mvc.perform(get(BASE + "/events/" + missing + "/master-data").header("Authorization", manager))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
            mvc.perform(get(BASE + "/events/abc/master-data").header("Authorization", manager))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(BASE + "/events/" + EVENT + "/master-data").header("Authorization", manager))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Tải ảnh lên")
    class TaiAnh {

        private static final String PATH = BASE + "/uploads/poster";

        @Test
        @DisplayName("xin URL ký sẵn: 200 kèm URL, URL công khai, trần kích thước")
        void thanh_cong() throws Exception {
            when(uploads.issue(ORG, "image/png"))
                    .thenReturn(new PosterUploadUseCase.Ticket(
                            "https://up.example/x",
                            "https://cdn.example/x.png",
                            "image/png",
                            8_388_608L,
                            Instant.parse("2026-10-22T03:05:00Z")));

            mvc.perform(post(PATH)
                            .header("Authorization", manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contentType\":\"image/png\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uploadUrl").value("https://up.example/x"))
                    .andExpect(jsonPath("$.maxBytes").value(8_388_608));
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(strings = {"{}", "{\"contentType\":\"\"}", "{\"contentType\":\"   \"}", "{\"contentType\":null}"})
        @DisplayName("thiếu hoặc trống contentType: 400, không ký gì")
        void thieu_kieu(String body) throws Exception {
            mvc.perform(post(PATH)
                            .header("Authorization", manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(uploads);
        }

        @Test
        @DisplayName("contentType dài 101 ký tự: 400")
        void kieu_qua_dai() throws Exception {
            mvc.perform(post(PATH)
                            .header("Authorization", manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contentType\":\"" + "a".repeat(101) + "\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("kiểu không cho phép (SVG/HTML): 422 từ use case, và request multipart không được nhận")
        void kieu_khong_cho_phep() throws Exception {
            when(uploads.issue(ORG, "image/svg+xml"))
                    .thenThrow(new ApiException(CatalogErrorCode.POSTER_URL_INVALID, "Chỉ nhận ảnh"));

            mvc.perform(post(PATH)
                            .header("Authorization", manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contentType\":\"image/svg+xml\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("POSTER_URL_INVALID"));
            mvc.perform(post(PATH).header("Authorization", manager).contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("không token: 401; tổ chức khác: 404")
        void khong_duoc_phep() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/png\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/v1/organizations/" + OTHER_ORG + "/uploads/poster")
                            .header("Authorization", manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contentType\":\"image/png\"}"))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(uploads);
        }
    }

    @Nested
    @DisplayName("Sơ đồ ghế và dựng từ khung")
    class SoDo {

        private static final String ZONE =
                "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":2,\"seatsPerRow\":2}";

        private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body) {
            return req.header("Authorization", manager)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{}",
                    "{\"zones\":[]}",
                    "{\"zones\":null}",
                    "{\"zones\":[{\"zoneCode\":\"A\"}]}",
                    "{\"zones\":[{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":2,\"seatsPerRow\":2,\"layoutShape\":\"HEXAGON\"}]}",
                    "{\"zones\":[{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":2,\"seatsPerRow\":2,\"innerRadius\":-1}]}",
                    "{\"stage\":{\"shape\":\"RECTANGLE\",\"x\":0,\"y\":0,\"width\":0},\"zones\":[" + ZONE + "]}",
                    "{\"stage\":{\"shape\":\"TRIANGLE\",\"x\":0,\"y\":0,\"width\":5},\"zones\":[" + ZONE + "]}",
                    "{\"stage\":{\"shape\":\"CIRCLE\",\"width\":5},\"zones\":[" + ZONE + "]}"
                })
        @DisplayName("sơ đồ rỗng, khu thiếu trường, hình lạ, bán kính âm, sân khấu hỏng: 400 ở cả lưu lẫn xem trước")
        void so_do_hong(String body) throws Exception {
            mvc.perform(json(put(BASE + "/venues/" + VENUE + "/zones"), body)).andExpect(status().isBadRequest());
            mvc.perform(json(post(BASE + "/venues/" + VENUE + "/floor-plan/preview"), body))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(configureZones, floorPlans);
        }

        @Test
        @DisplayName("hình bàn tròn (TABLE) mà domain và dữ liệu mẫu hỗ trợ phải đặt được qua API")
        void hinh_ban_tron() throws Exception {
            // ZoneRequest.layoutShape chỉ nhận GRID|ARC trong khi LayoutShape (và seed, và migration
            // V0106) có TABLE — nên khu bàn tròn không thể tạo qua HTTP, chỉ qua dữ liệu mẫu.
            String body = "{\"zones\":[{\"zoneCode\":\"T\",\"name\":\"Bàn\",\"kind\":\"SEATED\",\"rowCount\":2,"
                    + "\"seatsPerRow\":4,\"layoutShape\":\"TABLE\",\"originX\":0,\"originY\":0,\"innerRadius\":1.5}]}";
            when(configureZones.handle(any(), any(), any(), any()))
                    .thenReturn(new ConfigureVenueZonesHandler.Result(1, 0, 0, 0));
            when(adminQuery.venues(ORG))
                    .thenReturn(List.of(new CatalogViews.AdminVenue(VENUE, "V", "HN", null, 0, null, List.of())));

            mvc.perform(json(put(BASE + "/venues/" + VENUE + "/zones"), body)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("sơ đồ bị khoá (dựng từ khung): 409 VENUE_LAYOUT_LOCKED; đang dùng: 409 VENUE_IN_USE")
        void bi_khoa() throws Exception {
            when(configureZones.handle(any(), any(), any(), any()))
                    .thenThrow(new ApiException(CatalogErrorCode.VENUE_LAYOUT_LOCKED, "x"))
                    .thenThrow(new ApiException(CatalogErrorCode.VENUE_IN_USE, "x"));

            mvc.perform(json(put(BASE + "/venues/" + VENUE + "/zones"), "{\"zones\":[" + ZONE + "]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VENUE_LAYOUT_LOCKED"));
            mvc.perform(json(put(BASE + "/venues/" + VENUE + "/zones"), "{\"zones\":[" + ZONE + "]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VENUE_IN_USE"));
        }

        @Test
        @DisplayName("hình học không dựng được: 422 ZONE_LAYOUT_INVALID")
        void hinh_hoc_hong() throws Exception {
            when(configureZones.handle(any(), any(), any(), any()))
                    .thenThrow(new ApiException(CatalogErrorCode.ZONE_LAYOUT_INVALID, "cung quét ngược"));

            mvc.perform(json(put(BASE + "/venues/" + VENUE + "/zones"), "{\"zones\":[" + ZONE + "]}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ZONE_LAYOUT_INVALID"));
        }

        @Test
        @DisplayName("địa điểm của tổ chức khác (đường dẫn): 404; id địa điểm sai: 400")
        void dia_diem() throws Exception {
            mvc.perform(json(
                            put("/v1/organizations/" + OTHER_ORG + "/venues/" + VENUE + "/zones"),
                            "{\"zones\":[" + ZONE + "]}"))
                    .andExpect(status().isNotFound());
            mvc.perform(json(put(BASE + "/venues/abc/zones"), "{\"zones\":[" + ZONE + "]}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(configureZones);
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{}",
                    "{\"templateId\":\"not-uuid\"}",
                    "{\"templateId\":\"66666666-6666-4666-a666-666666666666\",\"title\":\"A\",\"venueName\":\"V\",\"city\":\"HN\"}",
                    "{\"templateId\":\"66666666-6666-4666-a666-666666666666\",\"title\":\"A\",\"venueName\":\"V\",\"city\":\"HN\",\"startsAt\":\"2026-12-01T12:00:00Z\",\"salesOpenAt\":\"2026-11-01T00:00:00Z\",\"salesCloseAt\":\"2026-11-30T00:00:00Z\",\"zonePrices\":{\"VIP\":-1}}",
                    "{\"templateId\":\"66666666-6666-4666-a666-666666666666\",\"title\":\"A\",\"venueName\":\"V\",\"city\":\"HN\",\"startsAt\":\"2026-12-01T12:00:00Z\",\"salesOpenAt\":\"2026-11-01T00:00:00Z\",\"salesCloseAt\":\"2026-11-30T00:00:00Z\",\"zonePrices\":{\"VIP\":\"re\"}}",
                    "{\"templateId\":\"66666666-6666-4666-a666-666666666666\",\"title\":\"A\",\"venueName\":\"V\",\"city\":\"HN\",\"startsAt\":\"2026-12-01T12:00:00Z\",\"salesOpenAt\":\"2026-11-01T00:00:00Z\",\"salesCloseAt\":\"2026-11-30T00:00:00Z\",\"maxSeatedPerHold\":0}"
                })
        @DisplayName("dựng từ khung thiếu trường bắt buộc, giá khu âm/sai kiểu, trần ≤ 0: 400")
        void tu_khung_hong(String body) throws Exception {
            mvc.perform(json(post(BASE + "/events/from-template"), body)).andExpect(status().isBadRequest());
            verifyNoInteractions(fromTemplate);
        }

        @Test
        @DisplayName("khung chưa mở: 409 TEMPLATE_NOT_USABLE; thiếu giá: 422 kèm meta.zoneCodes")
        void tu_khung_loi_nghiep_vu() throws Exception {
            String body =
                    "{\"templateId\":\"66666666-6666-4666-a666-666666666666\",\"title\":\"A\",\"venueName\":\"V\","
                            + "\"city\":\"HN\",\"startsAt\":\"2026-12-01T12:00:00Z\",\"salesOpenAt\":\"2026-11-01T00:00:00Z\","
                            + "\"salesCloseAt\":\"2026-11-30T00:00:00Z\"}";
            when(fromTemplate.handle(any(), any()))
                    .thenThrow(new ApiException(CatalogErrorCode.TEMPLATE_NOT_USABLE, "x"))
                    .thenThrow(new ApiException(
                            CatalogErrorCode.ZONE_PRICE_REQUIRED, "x", Map.of("zoneCodes", List.of("VIP"))));

            mvc.perform(json(post(BASE + "/events/from-template"), body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_USABLE"));
            mvc.perform(json(post(BASE + "/events/from-template"), body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.meta.zoneCodes[0]").value("VIP"));
        }

        @Test
        @DisplayName("ảnh sơ đồ: URL 501 ký tự → 400; hợp lệ → 204 không thân; use case từ chối → 422")
        void anh_so_do() throws Exception {
            mvc.perform(json(
                            put(BASE + "/venues/" + VENUE + "/seat-map-image"),
                            "{\"imageUrl\":\"" + "a".repeat(501) + "\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(seatMapImages);

            mvc.perform(json(
                            put(BASE + "/events/" + EVENT + "/seat-map-image"),
                            "{\"imageUrl\":\"https://cdn.example/a.png\"}"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            verify(seatMapImages).setForEvent(ORG, EVENT, "https://cdn.example/a.png");

            org.mockito.Mockito.doThrow(new ApiException(CatalogErrorCode.POSTER_URL_INVALID, "https"))
                    .when(seatMapImages)
                    .setForVenue(ORG, VENUE, "http://x.example/a.png");
            mvc.perform(json(
                            put(BASE + "/venues/" + VENUE + "/seat-map-image"),
                            "{\"imageUrl\":\"http://x.example/a.png\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("xem ảnh sơ đồ của sự kiện không có: 404 EVENT_NOT_FOUND")
        void xem_anh_khong_co() throws Exception {
            when(queries.seatMapImages(ORG, EVENT)).thenReturn(java.util.Optional.empty());

            mvc.perform(get(BASE + "/events/" + EVENT + "/seat-map-image").header("Authorization", manager))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        }
    }
}
