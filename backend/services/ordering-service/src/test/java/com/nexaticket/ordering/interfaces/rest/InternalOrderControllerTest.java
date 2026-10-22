// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import static com.nexaticket.ordering.support.WebSliceSecurity.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.application.command.ConfirmPaymentHandler;
import com.nexaticket.ordering.application.query.OrderQueries;
import com.nexaticket.ordering.support.WebSliceSecurity;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Open Host Service của ordering, với bí mật nội bộ ĐANG BẬT — đúng cấu hình production. Không có
 * {@code X-Internal-Token} đúng thì mọi đường {@code /internal/**} là 404, kể cả khi có JWT.
 */
@WebMvcTest(
        controllers = InternalOrderController.class,
        properties = "nexaticket.internal.shared-secret=" + InternalOrderControllerTest.SECRET)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import(WebSliceSecurity.class)
class InternalOrderControllerTest {

    static final String SECRET = "bi-mat-noi-bo-cho-test";
    private static final String HEADER = "X-Internal-Token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebSliceSecurity identities;

    @MockitoBean
    private OrderQueries queries;

    @MockitoBean
    private ConfirmPaymentHandler confirmPayment;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(HEADER, SECRET);
    }

    @Nested
    @DisplayName("Hàng rào X-Internal-Token")
    class Fence {

        @Test
        @DisplayName("Thiếu token: 404 (không phải 401), handler không chạy")
        void thieu_token_404() throws Exception {
            mvc.perform(post("/internal/orders/{id}/confirm-payment", UUID.randomUUID()))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(confirmPayment);
        }

        @Test
        @DisplayName("Token sai, kể cả chỉ khác một ký tự cuối: 404")
        void token_sai_404() throws Exception {
            mvc.perform(post("/internal/orders/{id}/confirm-payment", UUID.randomUUID())
                            .header(HEADER, SECRET.substring(0, SECRET.length() - 1) + "X"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/internal/orders/{id}", UUID.randomUUID()).header(HEADER, ""))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(confirmPayment, queries);
        }

        @Test
        @DisplayName("JWT người dùng hợp lệ KHÔNG thay được token nội bộ: vẫn 404")
        void jwt_nguoi_dung_khong_thay_token() throws Exception {
            mvc.perform(post("/internal/orders/{id}/confirm-payment", UUID.randomUUID())
                            .with(bearer(identities.customer())))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(confirmPayment);
        }
    }

    @Nested
    @DisplayName("POST /internal/orders/{id}/confirm-payment")
    class ConfirmPayment {

        @Test
        @DisplayName("Token đúng, không cần JWT: 200 kèm outcome để payment-service biết đừng giao lại")
        void tra_ve_outcome() throws Exception {
            UUID orderId = UUID.randomUUID();
            when(confirmPayment.handle(orderId)).thenReturn(ConfirmPaymentHandler.Outcome.MANUAL_REVIEW);

            mvc.perform(internal(post("/internal/orders/{id}/confirm-payment", orderId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("MANUAL_REVIEW"));
        }

        @Test
        @DisplayName("Đơn không tồn tại: 404 ORDER_NOT_FOUND dạng RFC 7807")
        void don_khong_ton_tai() throws Exception {
            UUID orderId = UUID.randomUUID();
            when(confirmPayment.handle(orderId))
                    .thenThrow(new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));

            mvc.perform(internal(post("/internal/orders/{id}/confirm-payment", orderId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/order-not-found"));
        }

        @Test
        @DisplayName("Lỗi tạm thời không lường trước: 500 không lộ thông điệp gốc — payment-service sẽ giao lại")
        void loi_tam_thoi_500() throws Exception {
            UUID orderId = UUID.randomUUID();
            when(confirmPayment.handle(orderId)).thenThrow(new IllegalStateException("jdbc:postgresql://db/ordering"));

            mvc.perform(internal(post("/internal/orders/{id}/confirm-payment", orderId)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("postgresql"))));
        }
    }

    @Nested
    @DisplayName("POST /internal/orders/statuses")
    class Statuses {

        @Test
        @DisplayName("Trả map trạng thái; đơn không tồn tại vắng mặt")
        void tra_map() throws Exception {
            UUID known = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            when(queries.statusesOf(List.of(known, unknown))).thenReturn(Map.of(known, "REFUNDED"));

            mvc.perform(internal(post("/internal/orders/statuses"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderIds\":[\"" + known + "\",\"" + unknown + "\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statuses['" + known + "']").value("REFUNDED"))
                    .andExpect(jsonPath("$.statuses['" + unknown + "']").doesNotExist());
        }

        @Test
        @DisplayName("Danh sách rỗng hoặc thiếu: 400 VALIDATION_FAILED chỉ đúng trường orderIds")
        void danh_sach_rong() throws Exception {
            for (String body : List.of("{\"orderIds\":[]}", "{}")) {
                mvc.perform(internal(post("/internal/orders/statuses"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.meta.fields.orderIds").exists());
            }
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("200 mã được, 201 mã bị chặn — không phải đường xuất cả bảng đơn")
        void tran_200_ma() throws Exception {
            when(queries.statusesOf(any())).thenReturn(Map.of());

            mvc.perform(internal(post("/internal/orders/statuses"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(idsBody(200)))
                    .andExpect(status().isOk());
            mvc.perform(internal(post("/internal/orders/statuses"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(idsBody(201)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.orderIds").exists());

            verify(queries).statusesOf(any());
        }

        @Test
        @DisplayName("Mã không phải UUID trong danh sách: 400 Malformed request body")
        void ma_khong_phai_uuid() throws Exception {
            mvc.perform(internal(post("/internal/orders/statuses"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderIds\":[\"abc\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
        }

        private static String idsBody(int count) {
            return IntStream.range(0, count)
                    .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                    .collect(Collectors.joining(",", "{\"orderIds\":[", "]}"));
        }
    }

    @Nested
    @DisplayName("GET /internal/orders/{id}")
    class Get {

        @Test
        @DisplayName("Bản nội bộ có hoa hồng cho Ledger/Payout")
        void ban_noi_bo_co_hoa_hong() throws Exception {
            UUID orderId = UUID.randomUUID();
            when(queries.internalById(orderId))
                    .thenReturn(new OrderQueries.InternalOrderView(
                            orderId,
                            "NT-260908-K7M2QP",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "PAID",
                            2_700_000,
                            500,
                            135_000,
                            "NT1000001",
                            java.time.Instant.parse("2026-09-08T10:05:00Z"),
                            2,
                            List.of()));

            mvc.perform(internal(get("/internal/orders/{id}", orderId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commissionVnd").value(135_000))
                    .andExpect(jsonPath("$.ticketCount").value(2));
        }

        @Test
        @DisplayName("Không tồn tại: 404 ORDER_NOT_FOUND")
        void khong_ton_tai() throws Exception {
            UUID orderId = UUID.randomUUID();
            when(queries.internalById(orderId))
                    .thenThrow(new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));

            mvc.perform(internal(get("/internal/orders/{id}", orderId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }
    }
}
