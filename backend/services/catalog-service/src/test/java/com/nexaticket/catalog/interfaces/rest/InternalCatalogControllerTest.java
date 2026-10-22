// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.CheckinSessionQuery;
import com.nexaticket.catalog.application.query.PricingQuery;
import com.nexaticket.catalog.support.WebSliceBase;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Open Host Service của Catalog, với bí mật nội bộ ĐANG BẬT như production.
 *
 * <p>{@link PricingQuery} là bản thật (kho mock) để chứng minh hai điều ordering dựa vào: hoa hồng
 * là cấu hình phía server — request không có cách nào đặt nó — và mọi tham số hỏng là 4xx.
 */
@WebMvcTest(
        controllers = InternalCatalogController.class,
        properties = {
            "nexaticket.internal.shared-secret=" + WebSliceBase.INTERNAL_SECRET,
            "nexaticket.catalog.commission-bps=500"
        })
@Import(PricingQuery.class)
class InternalCatalogControllerTest extends WebSliceBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");

    @MockitoBean
    CatalogQueries queries;

    @MockitoBean
    CheckinSessionQuery checkin;

    @Test
    @DisplayName("giá: hoa hồng lấy từ cấu hình server, giảm giá 0, đúng tên field ordering đọc")
    void gia() throws Exception {
        when(queries.sessionExists(SESSION)).thenReturn(true);

        mvc.perform(get("/internal/sessions/{id}/pricing", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                        .param("subtotalVnd", "1500000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionBps").value(500))
                .andExpect(jsonPath("$.discountVnd").value(0));
    }

    @Test
    @DisplayName("tham số giả (commissionBps, discountVnd) do người gọi gửi lên bị bỏ qua")
    void tham_so_gia_bi_bo_qua() throws Exception {
        when(queries.sessionExists(SESSION)).thenReturn(true);

        mvc.perform(get("/internal/sessions/{id}/pricing", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                        .param("commissionBps", "0")
                        .param("discountVnd", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionBps").value(500))
                .andExpect(jsonPath("$.discountVnd").value(0));
    }

    @Test
    @DisplayName("mã khuyến mãi rỗng (UriComponentsBuilder vẫn gắn tên tham số): coi như không nhập")
    void ma_rong() throws Exception {
        when(queries.sessionExists(SESSION)).thenReturn(true);

        mvc.perform(get("/internal/sessions/{id}/pricing", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                        .param("promotionCode", ""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("có mã khuyến mãi: 422 PROMOTION_INVALID — câu trả lời dứt khoát, khác 404")
    void co_ma() throws Exception {
        when(queries.sessionExists(SESSION)).thenReturn(true);

        mvc.perform(get("/internal/sessions/{id}/pricing", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                        .param("promotionCode", "GIAM50"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CatalogErrorCode.PROMOTION_INVALID.code()));
    }

    @Test
    @DisplayName("suất không tồn tại: 404 SESSION_NOT_FOUND có thân (phân biệt với 404 của bộ lọc)")
    void suat_khong_ton_tai() throws Exception {
        mvc.perform(get("/internal/sessions/{id}/pricing", SESSION).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @ParameterizedTest(name = "subtotalVnd={0}")
    @ValueSource(strings = {"-1", "abc", "1.5", "99999999999999999999"})
    @DisplayName("tạm tính âm / không phải số / tràn long: 4xx, không phải 500")
    void tam_tinh_hong(String value) throws Exception {
        when(queries.sessionExists(SESSION)).thenReturn(true);

        mvc.perform(get("/internal/sessions/{id}/pricing", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                        .param("subtotalVnd", value))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("id suất không phải UUID: 400")
    void id_sai() throws Exception {
        mvc.perform(get("/internal/sessions/abc/pricing").header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(queries);
    }

    @ParameterizedTest(name = "#{index}")
    @ValueSource(strings = {"", "sai-bi-mat"})
    @DisplayName("thiếu hoặc sai bí mật nội bộ: 404 không thân, không chạm truy vấn — kể cả khi có JWT hợp lệ")
    void khong_duoc_phep(String header) throws Exception {
        var request = get("/internal/sessions/{id}/pricing", SESSION);
        if (!header.isEmpty()) {
            request = request.header(InternalApiFilter.HEADER, header);
        }
        request = request.header("Authorization", bearer(customerToken(UUID.randomUUID())));

        mvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verifyNoInteractions(queries, checkin);
    }

    @Test
    @DisplayName("thông tin suất cho soát vé: 200 đúng tên field; suất không có: 404 có mã")
    void thong_tin_suat() throws Exception {
        UUID event = UUID.randomUUID();
        when(checkin.info(SESSION))
                .thenReturn(new CatalogViews.SessionInfo(
                        SESSION, event, "Đêm nhạc", Instant.parse("2026-12-01T12:00:00Z"), null));

        mvc.perform(get("/internal/sessions/{id}", SESSION).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventSessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.eventTitle").value("Đêm nhạc"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-01T12:00:00Z"))
                .andExpect(jsonPath("$.endsAt").doesNotExist());

        UUID missing = UUID.randomUUID();
        when(checkin.info(missing))
                .thenThrow(new com.nexaticket.platform.web.error.ApiException(
                        CatalogErrorCode.SESSION_NOT_FOUND, "Session not found"));
        mvc.perform(get("/internal/sessions/{id}", missing).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
}
