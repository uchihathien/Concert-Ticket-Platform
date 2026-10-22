// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.application.query.SeatMapView;
import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.inventory.support.WebSliceTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Sơ đồ chỗ công khai: ETag theo version VÀ người xem, 304, no-cache. */
@WebMvcTest(SeatMapController.class)
class SeatMapControllerTest extends WebSliceTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-a000-0000000000b2");
    private static final UUID SEAT = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID TYPE = UUID.fromString("00000000-0000-4000-a000-000000000011");

    @MockitoBean
    SeatQueries queries;

    private static SeatMapView view(SeatMapView.PurchaseAllowance allowance) {
        return new SeatMapView(
                SESSION,
                17L,
                List.of(new SeatMapView.Seat(
                        SEAT,
                        "A-1-1",
                        "A",
                        "Khan dai A",
                        "1",
                        "1",
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        TYPE,
                        "Ve ngoi",
                        1_500_000L,
                        "AVAILABLE")),
                List.of(new SeatMapView.StandingZone("GA", TYPE, "Ve dung", 800_000L, 12, 3000)),
                allowance);
    }

    @Nested
    @DisplayName("Khách ẩn danh")
    class AnDanh {

        @Test
        @DisplayName(
                "Không token vẫn xem được: 200, ETag yếu W/\"version-anon\", Cache-Control no-cache, không có hạn mức")
        void an_danh_xem_duoc() throws Exception {
            when(queries.seatMap(SESSION, null)).thenReturn(view(null));

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, "W/\"17-anon\""))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                    .andExpect(jsonPath("$.availabilityVersion").value(17))
                    .andExpect(jsonPath("$.seats[0].seatCode").value("A-1-1"))
                    .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.standingZones[0].available").value(12))
                    .andExpect(jsonPath("$.purchaseAllowance").doesNotExist());

            verify(queries).seatMap(SESSION, null);
        }

        @Test
        @DisplayName("If-None-Match khớp: 304 body rỗng, vẫn gửi lại ETag")
        void etag_khop_304() throws Exception {
            when(queries.availabilityVersion(SESSION)).thenReturn(17L);

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION).header(HttpHeaders.IF_NONE_MATCH, "W/\"17-anon\""))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string(HttpHeaders.ETAG, "W/\"17-anon\""))
                    .andExpect(content().string(""));

            // Điểm của 304 sớm: không dựng sơ đồ (hàng chục nghìn ghế) chỉ để vứt đi.
            verify(queries, never()).seatMap(any(), any());
        }

        @Test
        @DisplayName("Client cũ còn giữ ETag mạnh \"17-anon\" của bản trước vẫn nhận 304 (so sánh yếu, RFC 9110)")
        void etag_manh_cu_van_khop() throws Exception {
            when(queries.availabilityVersion(SESSION)).thenReturn(17L);

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION).header(HttpHeaders.IF_NONE_MATCH, "\"17-anon\""))
                    .andExpect(status().isNotModified());
        }

        @Test
        @DisplayName("If-None-Match là danh sách hoặc *: khớp một phần tử là 304")
        void etag_danh_sach_va_sao() throws Exception {
            when(queries.availabilityVersion(SESSION)).thenReturn(17L);

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION)
                            .header(HttpHeaders.IF_NONE_MATCH, "W/\"3-anon\", W/\"17-anon\""))
                    .andExpect(status().isNotModified());
            mvc.perform(get("/v1/sessions/{id}/seats", SESSION).header(HttpHeaders.IF_NONE_MATCH, "*"))
                    .andExpect(status().isNotModified());
        }

        @Test
        @DisplayName("If-None-Match của version cũ: 200 với dữ liệu mới")
        void etag_cu_200() throws Exception {
            when(queries.seatMap(SESSION, null)).thenReturn(view(null));

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION).header(HttpHeaders.IF_NONE_MATCH, "\"16-anon\""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.availabilityVersion").value(17));
        }
    }

    @Nested
    @DisplayName("Khách đã đăng nhập")
    class DaDangNhap {

        @Test
        @DisplayName("ETag mang userId và body có hạn mức của chính người đó")
        void etag_theo_nguoi_xem() throws Exception {
            String token = customerToken(USER);
            when(queries.seatMap(SESSION, USER)).thenReturn(view(new SeatMapView.PurchaseAllowance(10, 4, 6)));

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, "W/\"17-" + USER + "\""))
                    .andExpect(jsonPath("$.purchaseAllowance.limit").value(10))
                    .andExpect(jsonPath("$.purchaseAllowance.used").value(4))
                    .andExpect(jsonPath("$.purchaseAllowance.remaining").value(6));
        }

        @Test
        @DisplayName("ETag của người khác (hoặc bản ẩn danh) không cho 304 — không dùng chung hạn mức của nhau")
        void etag_nguoi_khac_khong_304() throws Exception {
            String token = customerToken(USER);
            when(queries.seatMap(SESSION, USER)).thenReturn(view(new SeatMapView.PurchaseAllowance(10, 4, 6)));

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .header(HttpHeaders.IF_NONE_MATCH, "W/\"17-" + OTHER + "\""))
                    .andExpect(status().isOk());
            mvc.perform(get("/v1/sessions/{id}/seats", SESSION)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .header(HttpHeaders.IF_NONE_MATCH, "W/\"17-anon\""))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("JWT hợp lệ nhưng identity không trả lời: vẫn xem được như khách ẩn danh")
        void identity_chet_van_xem_an_danh() throws Exception {
            String token = tokenWithoutIdentity(USER);
            when(queries.seatMap(any(), isNull())).thenReturn(view(null));

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, "W/\"17-anon\""));
        }
    }

    @Nested
    @DisplayName("Lỗi")
    class Loi {

        @Test
        @DisplayName("Suất chưa dựng tồn kho: 404 SESSION_NOT_FOUND dạng RFC 7807")
        void suat_khong_ton_tai() throws Exception {
            when(queries.seatMap(SESSION, null))
                    .thenThrow(new ApiException(InventoryErrorCode.SESSION_NOT_FOUND, "Event session not found"));

            mvc.perform(get("/v1/sessions/{id}/seats", SESSION))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/session-not-found"));
        }

        @Test
        @DisplayName("Mã suất không phải UUID trên endpoint công khai: 400, không phải 500 kèm log ERROR")
        void ma_suat_khong_phai_uuid() throws Exception {
            mvc.perform(get("/v1/sessions/{id}/seats", "khong-phai-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(queries);
        }
    }
}
