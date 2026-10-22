// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.interfaces.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.analytics.application.query.SalesQueries;
import com.nexaticket.analytics.support.WebSliceTestBase;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Open Host Service của analytics: chỉ service nội bộ (mang {@code X-Internal-Token}) gọi được,
 * JWT của người dùng không thay được bí mật đó.
 */
@WebMvcTest(
        controllers = InternalSalesController.class,
        properties = "nexaticket.internal.shared-secret=" + WebSliceTestBase.INTERNAL_SECRET)
class InternalSalesControllerTest extends WebSliceTestBase {

    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID ORG = UUID.fromString("33333333-3333-4333-a333-333333333333");
    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-a111-111111111111");

    @MockitoBean
    SalesQueries queries;

    @Test
    @DisplayName("byEvent với token đúng: 200, tên field khớp hợp đồng với catalog")
    void theo_su_kien() throws Exception {
        when(queries.forEvent(EVENT))
                .thenReturn(List.of(new SalesQueries.SessionSalesView(SESSION, EVENT, 3, 900_000, 1, 0, 0)));

        mvc.perform(get("/internal/events/{id}/sales", EVENT).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].eventSessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.sessions[0].eventId").value(EVENT.toString()))
                .andExpect(jsonPath("$.sessions[0].ticketsSold").value(3))
                .andExpect(jsonPath("$.sessions[0].grossVnd").value(900_000))
                .andExpect(jsonPath("$.sessions[0].ordersPaid").value(1))
                .andExpect(jsonPath("$.sessions[0].ordersExpired").value(0))
                .andExpect(jsonPath("$.sessions[0].ordersCancelled").value(0));
    }

    @Test
    @DisplayName("sự kiện chưa bán vé nào: 200 với danh sách rỗng, không phải 404")
    void chua_ban() throws Exception {
        when(queries.forEvent(EVENT)).thenReturn(List.of());

        mvc.perform(get("/internal/events/{id}/sales", EVENT).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions").isEmpty());
    }

    @Test
    @DisplayName("byOrganization: trả các suất của tổ chức, bỏ phần tổng")
    void theo_to_chuc() throws Exception {
        when(queries.forOrganization(ORG))
                .thenReturn(new SalesQueries.OrganizationSummary(
                        ORG,
                        3,
                        900_000,
                        List.of(new SalesQueries.SessionSalesView(SESSION, EVENT, 3, 900_000, 1, 0, 0))));

        mvc.perform(get("/internal/organizations/{id}/sales", ORG).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.totalGrossVnd").doesNotExist());
    }

    @Test
    @DisplayName("trending: mặc định limit 8, trả eventId và ticketsSold")
    void trending() throws Exception {
        when(queries.trending(8)).thenReturn(List.of(new SalesQueries.TrendingView(EVENT, 90)));

        mvc.perform(get("/internal/events/trending").header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventId").value(EVENT.toString()))
                .andExpect(jsonPath("$.events[0].ticketsSold").value(90));
        verify(queries).trending(8);
    }

    @Test
    @DisplayName("trending nhận limit từ query")
    void trending_limit() throws Exception {
        when(queries.trending(3)).thenReturn(List.of());

        mvc.perform(get("/internal/events/trending")
                        .param("limit", "3")
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk());
        verify(queries).trending(3);
    }

    @Test
    @DisplayName("limit không phải số: 400, không phải 500")
    void trending_limit_sai_kieu() throws Exception {
        mvc.perform(get("/internal/events/trending")
                        .param("limit", "nhieu")
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("không có token nội bộ: 404 không thân, không chạm truy vấn")
    void khong_token() throws Exception {
        mvc.perform(get("/internal/events/{id}/sales", EVENT))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mvc.perform(get("/internal/events/trending")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/organizations/{id}/sales", ORG)).andExpect(status().isNotFound());

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("token nội bộ sai: 404")
    void token_sai() throws Exception {
        mvc.perform(get("/internal/events/{id}/sales", EVENT).header(InternalApiFilter.HEADER, "sai"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("JWT của người dùng KHÔNG thay được bí mật nội bộ")
    void jwt_khong_thay_duoc_bi_mat() throws Exception {
        String token = customerToken(UUID.randomUUID());

        mvc.perform(get("/internal/events/{id}/sales", EVENT).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(queries);
    }
}
