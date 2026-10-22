// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import static com.nexaticket.ordering.support.WebSliceSecurity.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.ordering.application.query.SalesStats;
import com.nexaticket.ordering.application.query.SalesStatsQuery;
import com.nexaticket.ordering.support.WebSliceSecurity;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thống kê bán hàng cho ban tổ chức: hai lớp chốt (TenantFilter → 404 cho người ngoài,
 * PermissionGuard → 403 cho thành viên thiếu CATALOG_MANAGE) và kiểm tham số thành 400.
 */
@WebMvcTest(controllers = OrganizationStatsController.class)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import(WebSliceSecurity.class)
class OrganizationStatsControllerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String SUMMARY = "/v1/organizations/{org}/stats/orders/summary";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebSliceSecurity identities;

    @MockitoBean
    private SalesStatsQuery stats;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID manager() {
        return identities.member(ORG, Role.EVENT_MANAGER);
    }

    private static SalesStats.Summary emptySummary() {
        SalesStats.Period period = new SalesStats.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7));
        return new SalesStats.Summary(period, new SalesStats.Totals(0, 0, 0, 0, 0, 0, 0, 0, null, 0), null, null);
    }

    @Nested
    @DisplayName("Phân quyền")
    class Authorization {

        @Test
        @DisplayName("Không token: 401")
        void khong_token() throws Exception {
            mvc.perform(get(SUMMARY, ORG).param("from", "2026-09-01").param("to", "2026-09-07"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Thành viên tổ chức KHÁC: 404 — không xác nhận tổ chức này tồn tại")
        void to_chuc_khac_404() throws Exception {
            UUID outsider = identities.member(UUID.randomUUID(), Role.ORG_OWNER);

            mvc.perform(get(SUMMARY, ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .with(bearer(outsider)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Khách mua vé (không thuộc tổ chức nào): 404")
        void khach_404() throws Exception {
            mvc.perform(get(SUMMARY, ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .with(bearer(identities.customer())))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Nhân viên soát vé là thành viên nhưng thiếu CATALOG_MANAGE: 403 FORBIDDEN")
        void soat_ve_403() throws Exception {
            UUID staff = identities.member(ORG, Role.CHECKIN_STAFF);

            mvc.perform(get(SUMMARY, ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .with(bearer(staff)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            verifyNoInteractions(stats);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"ORG_OWNER", "ORG_ADMIN", "EVENT_MANAGER"})
        @DisplayName("Vai trò có CATALOG_MANAGE: 200, truy vấn đúng tổ chức trên đường dẫn")
        void vai_tro_du_quyen(Role role) throws Exception {
            when(stats.summary(any(), anyBoolean())).thenReturn(emptySummary());

            mvc.perform(get(SUMMARY, ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .with(bearer(identities.member(ORG, role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current.grossVnd").value(0));

            ArgumentCaptor<SalesStatsQuery.Request> request = ArgumentCaptor.forClass(SalesStatsQuery.Request.class);
            verify(stats).summary(request.capture(), eq(false));
            assertThat(request.getValue().organizationId()).isEqualTo(ORG);
            assertThat(request.getValue().from()).isEqualTo(LocalDate.of(2026, 9, 1));
        }

        @Test
        @DisplayName("Superadmin không phải thành viên: route tổ chức vẫn 404 — đường của họ là /v1/platform/**")
        void superadmin_khong_phai_thanh_vien() throws Exception {
            mvc.perform(get(SUMMARY, ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .with(bearer(identities.superAdmin())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Mã tổ chức mã hoá phần trăm (vượt rào kiểu TenantPath): 404")
        void ma_hoa_phan_tram_404() throws Exception {
            UUID owner = identities.member(ORG, Role.ORG_OWNER);
            // Mã hoá phần trăm ký tự đầu của UUID: Spring vẫn giải mã ra đúng UUID cho @PathVariable.
            String encoded = String.format("%%%02X", (int) ORG.toString().charAt(0))
                    + ORG.toString().substring(1);

            mvc.perform(get(java.net.URI.create("/v1/organizations/" + encoded
                                    + "/stats/orders/summary?from=2026-09-01&to=2026-09-07"))
                            .with(bearer(owner)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(stats);
        }
    }

    @Nested
    @DisplayName("Kiểm tham số")
    class Validation {

        @ParameterizedTest(name = "from={0}")
        @ValueSource(strings = {"2026-13-01", "01/09/2026", "hom-nay", ""})
        @DisplayName("Ngày sai định dạng: 400 VALIDATION_FAILED, không chạm truy vấn")
        void ngay_sai_dinh_dang(String from) throws Exception {
            mvc.perform(get(SUMMARY, ORG)
                            .param("from", from)
                            .param("to", "2026-09-07")
                            .with(bearer(manager())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("from phải có dạng YYYY-MM-DD"));

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Mã sự kiện trong bộ lọc không phải UUID: 400")
        void ma_loc_sai() throws Exception {
            mvc.perform(get("/v1/organizations/{org}/stats/orders/breakdown", ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .param("eventId", UUID.randomUUID().toString(), "khong-phai-uuid")
                            .with(bearer(manager())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Bộ lọc nhiều giá trị được đọc thành danh sách UUID")
        void bo_loc_nhieu_gia_tri() throws Exception {
            UUID e1 = UUID.randomUUID();
            UUID e2 = UUID.randomUUID();
            when(stats.heatmap(any())).thenReturn(new SalesStats.Heatmap(null, List.of()));

            mvc.perform(get("/v1/organizations/{org}/stats/orders/heatmap", ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .param("eventId", e1.toString(), e2.toString())
                            .with(bearer(manager())))
                    .andExpect(status().isOk());

            ArgumentCaptor<SalesStatsQuery.Request> request = ArgumentCaptor.forClass(SalesStatsQuery.Request.class);
            verify(stats).heatmap(request.capture());
            assertThat(request.getValue().eventIds()).containsExactly(e1, e2);
            assertThat(request.getValue().sessionIds()).isNull();
        }

        @Test
        @DisplayName("Lỗi kiểm tra từ tầng query (kỳ quá dài) đi ra 400 RFC 7807")
        void loi_tu_query_ra_400() throws Exception {
            when(stats.summary(any(), anyBoolean()))
                    .thenThrow(new ApiException(
                            ErrorCode.Common.VALIDATION_FAILED, "Khoảng thời gian tối đa là 731 ngày"));

            mvc.perform(get(SUMMARY, ORG)
                            .param("from", "2020-01-01")
                            .param("to", "2026-09-07")
                            .with(bearer(manager())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("Khoảng thời gian tối đa là 731 ngày"));
        }

        @Test
        @DisplayName("Danh sách đơn: mặc định trang 0 cỡ 20, chuyển nguyên trạng thái và từ khoá xuống")
        void danh_sach_mac_dinh() throws Exception {
            when(stats.orders(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new SalesStats.OrderPage(null, List.of(), 0, 0, 20));

            mvc.perform(get("/v1/organizations/{org}/stats/orders/list", ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .param("status", "paid", "expired")
                            .param("query", "NT-26")
                            .with(bearer(manager())))
                    .andExpect(status().isOk());

            verify(stats).orders(any(), eq(List.of("paid", "expired")), eq("NT-26"), eq(0), eq(20));
        }

        @Test
        @DisplayName("Thiếu tham số bắt buộc from: lỗi của người gọi (400), không phải 500")
        void thieu_from() throws Exception {
            mvc.perform(get(SUMMARY, ORG).param("to", "2026-09-07").with(bearer(manager())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("bucket mặc định là day, compare mặc định false")
        void mac_dinh_timeseries() throws Exception {
            when(stats.series(any(), any(), anyBoolean()))
                    .thenReturn(new SalesStats.Series("day", null, List.of(), null, null));

            mvc.perform(get("/v1/organizations/{org}/stats/orders/timeseries", ORG)
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-07")
                            .with(bearer(manager())))
                    .andExpect(status().isOk());

            verify(stats).series(any(), eq("day"), eq(false));
        }
    }
}
