// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.analytics.application.query.SalesQueries;
import com.nexaticket.analytics.support.WebSliceTestBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Dashboard doanh thu của tổ chức, qua chuỗi bảo mật THẬT của starter-security.
 *
 * <p>Ranh giới cần giữ: chỉ người của tổ chức đó và chỉ người có quyền xem số liệu mới thấy tiền.
 * Một thành viên của tổ chức khác nhận 404 (không xác nhận tổ chức tồn tại); khách hàng và người
 * ẩn danh không thấy gì.
 */
@WebMvcTest(controllers = SalesDashboardController.class)
class SalesDashboardControllerTest extends WebSliceTestBase {

    private static final UUID ORG = UUID.fromString("aaaaaaaa-0000-4000-a000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("bbbbbbbb-0000-4000-a000-000000000002");
    private static final String URL = "/v1/admin/organizations/{id}/sales";

    @MockitoBean
    SalesQueries queries;

    private static SalesQueries.OrganizationSummary summary() {
        return new SalesQueries.OrganizationSummary(
                ORG,
                15,
                7_500_000L,
                List.of(new SalesQueries.SessionSalesView(
                        UUID.fromString("11111111-1111-4111-a111-111111111111"),
                        UUID.fromString("22222222-2222-4222-a222-222222222222"),
                        15,
                        7_500_000L,
                        6,
                        1,
                        1)));
    }

    private String tokenIn(Role role, UUID org) {
        return tokenFor(UUID.randomUUID(), Map.of(TenantId.of(org), role), false);
    }

    @Test
    @DisplayName("ORG_ADMIN của đúng tổ chức: 200 với đủ số vé và tiền, và KHÔNG có trường hoa hồng/số dư")
    void admin_cua_to_chuc_xem_duoc() throws Exception {
        when(queries.forOrganization(ORG)).thenReturn(summary());

        mvc.perform(get(URL, ORG).header(HttpHeaders.AUTHORIZATION, bearer(tokenIn(Role.ORG_ADMIN, ORG))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG.toString()))
                .andExpect(jsonPath("$.totalTicketsSold").value(15))
                .andExpect(jsonPath("$.totalGrossVnd").value(7_500_000))
                .andExpect(jsonPath("$.sessions[0].ticketsSold").value(15))
                .andExpect(jsonPath("$.sessions[0].ordersExpired").value(1))
                .andExpect(jsonPath("$.commission").doesNotExist())
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andExpect(jsonPath("$.payout").doesNotExist());
    }

    @Test
    @DisplayName("ORG_OWNER của đúng tổ chức: 200")
    void owner_xem_duoc() throws Exception {
        when(queries.forOrganization(ORG)).thenReturn(summary());

        mvc.perform(get(URL, ORG).header(HttpHeaders.AUTHORIZATION, bearer(tokenIn(Role.ORG_OWNER, ORG))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("không có Authorization: 401, không chạm truy vấn")
    void an_danh_401() throws Exception {
        mvc.perform(get(URL, ORG)).andExpect(status().isUnauthorized());

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("token không giải mã được: 401")
    void token_hong_401() throws Exception {
        mvc.perform(get(URL, ORG).header(HttpHeaders.AUTHORIZATION, bearer("rac")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("admin của tổ chức KHÁC: 404, không phải 403, và không chạm truy vấn")
    void admin_to_chuc_khac_404() throws Exception {
        mvc.perform(get(URL, ORG).header(HttpHeaders.AUTHORIZATION, bearer(tokenIn(Role.ORG_ADMIN, OTHER_ORG))))
                .andExpect(status().isNotFound());

        verify(queries, never()).forOrganization(any());
    }

    @Test
    @DisplayName("khách hàng (không thuộc tổ chức nào): 404")
    void khach_hang_404() throws Exception {
        mvc.perform(get(URL, ORG).header(HttpHeaders.AUTHORIZATION, bearer(customerToken(UUID.randomUUID()))))
                .andExpect(status().isNotFound());

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("UUID mã hoá phần trăm trỏ sang tổ chức khác: 404 — không vượt rào bằng đường dẫn biến dạng")
    void duong_dan_ma_hoa_404() throws Exception {
        String token = tokenIn(Role.ORG_ADMIN, OTHER_ORG);

        mvc.perform(get(java.net.URI.create("/v1/admin/organizations/%61aaaaaa-0000-4000-a000-000000000001/sales"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("tổ chức không phải UUID: 404 chứ không phải 500")
    void id_khong_phai_uuid() throws Exception {
        mvc.perform(get(URL, "acme").header(HttpHeaders.AUTHORIZATION, bearer(tokenIn(Role.ORG_ADMIN, ORG))))
                .andExpect(status().isNotFound());
    }

    /**
     * Nhân viên soát vé là thành viên của tổ chức nhưng không có {@code ORG_METRICS_VIEW}
     * ({@code Role.CHECKIN_STAFF} chỉ có {@code CHECKIN_SCAN}). Controller chỉ dựa vào
     * {@code TenantFilter} (membership) và không khai {@code @RequiresPermission}, nên họ đọc được
     * doanh thu của cả tổ chức.
     */
    @Test
    @DisplayName("CHECKIN_STAFF của tổ chức: 403 — nhân viên soát vé không được xem doanh thu")
    void nhan_vien_soat_ve_403() throws Exception {
        mvc.perform(get(URL, ORG).header(HttpHeaders.AUTHORIZATION, bearer(tokenIn(Role.CHECKIN_STAFF, ORG))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(queries);
    }
}
