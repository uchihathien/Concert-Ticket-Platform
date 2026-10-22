// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import static com.nexaticket.ordering.support.WebSliceSecurity.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.application.command.CloseOrderHandler;
import com.nexaticket.ordering.application.command.PlaceOrderHandler;
import com.nexaticket.ordering.application.query.OrderQueries;
import com.nexaticket.ordering.application.query.OrderView;
import com.nexaticket.ordering.support.WebSliceSecurity;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API đơn hàng của khách qua chuỗi filter bảo mật THẬT của starter-security và bộ xử lý lỗi RFC 7807
 * của starter-web. Tầng application là mock: ở đây chỉ kiểm hợp đồng HTTP.
 */
@WebMvcTest(controllers = OrderController.class)
@ImportAutoConfiguration({
    SecurityAutoConfiguration.class,
    WebAutoConfiguration.class,
    com.nexaticket.platform.idempotency.IdempotencyAutoConfiguration.class
})
@Import(WebSliceSecurity.class)
class OrderControllerTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebSliceSecurity identities;

    @MockitoBean
    private PlaceOrderHandler placeOrder;

    @MockitoBean
    private CloseOrderHandler closeOrder;

    @MockitoBean
    private OrderQueries queries;

    /** Không gọi Keycloak: JWT trong test đã được "giải mã" sẵn bằng post-processor. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static OrderView view(UUID orderId) {
        return new OrderView(
                orderId,
                "NT-260908-K7M2QP",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "AWAITING_PAYMENT",
                3_000_000,
                0,
                3_000_000,
                "NT1000001",
                "0002...",
                "https://pay.payos.vn/web/1",
                Instant.parse("2026-09-08T10:15:00Z"),
                null,
                List.of());
    }

    @Nested
    @DisplayName("Xác thực")
    class Authentication {

        @Test
        @DisplayName("Không có token: 401 từ Spring Security, không chạm tầng application")
        void khong_token_401() throws Exception {
            mvc.perform(get("/v1/me/orders")).andExpect(status().isUnauthorized());

            verifyNoInteractions(queries, placeOrder, closeOrder);
        }

        @Test
        @DisplayName("Token hợp lệ nhưng identity không nhận ra: 401 UNAUTHENTICATED dạng RFC 7807")
        void identity_khong_nhan_ra_401() throws Exception {
            mvc.perform(get("/v1/me/orders").with(bearer(UUID.randomUUID())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/unauthenticated"));

            verifyNoInteractions(queries);
        }
    }

    @Nested
    @DisplayName("POST /v1/orders")
    class Place {

        @Test
        @DisplayName("201 kèm link thanh toán; userId lấy từ token, KHÔNG từ body")
        void dat_don_thanh_cong() throws Exception {
            UUID user = identities.customer();
            UUID holdId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            when(placeOrder.handle(any()))
                    .thenReturn(new PlaceOrderHandler.Result(
                            orderId,
                            "NT-260908-K7M2QP",
                            2_700_000,
                            "NT1000001",
                            "0002...",
                            "https://pay.payos.vn/web/1",
                            Instant.parse("2026-09-08T10:15:00Z")));

            mvc.perform(post("/v1/orders")
                            .with(bearer(user))
                            .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {"holdId":"%s","promotionCode":"SALE","userId":"%s","totalVnd":1}
                                    """
                                            .formatted(holdId, UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.totalVnd").value(2_700_000))
                    .andExpect(jsonPath("$.checkoutUrl").value("https://pay.payos.vn/web/1"))
                    .andExpect(jsonPath("$.paymentExpiresAt").value("2026-09-08T10:15:00Z"));

            ArgumentCaptor<PlaceOrderHandler.Command> cmd = ArgumentCaptor.forClass(PlaceOrderHandler.Command.class);
            verify(placeOrder).handle(cmd.capture());
            assertThat(cmd.getValue()).isEqualTo(new PlaceOrderHandler.Command(holdId, user, "SALE"));
        }

        @Test
        @DisplayName("Thiếu holdId: 400 VALIDATION_FAILED chỉ ra đúng trường")
        void thieu_hold_id() throws Exception {
            mvc.perform(post("/v1/orders")
                            .with(bearer(identities.customer()))
                            .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"promotionCode\":\"SALE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.meta.fields.holdId").exists());

            verifyNoInteractions(placeOrder);
        }

        @Test
        @DisplayName("JSON hỏng hoặc holdId không phải UUID: 400, không phải 500")
        void json_hong() throws Exception {
            mvc.perform(post("/v1/orders")
                            .with(bearer(identities.customer()))
                            .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdId\":\"khong-phai-uuid\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));

            mvc.perform(post("/v1/orders")
                            .with(bearer(identities.customer()))
                            .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdId\":"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(placeOrder);
        }

        @Test
        @DisplayName("Lỗi nghiệp vụ của saga đi ra đúng mã và HTTP status")
        void loi_nghiep_vu_ra_dung_ma() throws Exception {
            UUID user = identities.customer();
            when(placeOrder.handle(any()))
                    .thenThrow(new ApiException(OrderingErrorCode.HOLD_EXPIRED, "hold expired"))
                    .thenThrow(new ApiException(OrderingErrorCode.PROMOTION_INVALID, "bad promo"))
                    .thenThrow(new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "retry"));
            String body = "{\"holdId\":\"" + UUID.randomUUID() + "\"}";

            for (int[] expected : new int[][] {{409}, {400}, {503}}) {
                mvc.perform(post("/v1/orders")
                                .with(bearer(user))
                                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().is(expected[0]))
                        .andExpect(jsonPath("$.status").value(expected[0]))
                        .andExpect(header().exists("X-Correlation-Id"));
            }
        }

        @Test
        @DisplayName("Saga hỏng tạm thời (CHECKOUT_UNAVAILABLE) trả body RFC 7807 đủ type/title/code/correlationId")
        void body_loi_dung_hinh_dang() throws Exception {
            when(placeOrder.handle(any()))
                    .thenThrow(new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "Inventory unavailable"));

            mvc.perform(post("/v1/orders")
                            .with(bearer(identities.customer()))
                            .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                            .header("X-Correlation-Id", "corr-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/checkout-unavailable"))
                    .andExpect(jsonPath("$.title").value("checkout unavailable"))
                    .andExpect(jsonPath("$.code").value("CHECKOUT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.detail").value("Inventory unavailable"))
                    .andExpect(jsonPath("$.correlationId").value("corr-123"));
        }

        /**
         * Javadoc của OrderController hứa: POST bắt buộc có Idempotency-Key, filter của
         * starter-idempotency chặn trước. Không có filter nào như vậy trong codebase — starter chỉ có
         * IdempotencyStore — nên lưới thứ nhất chống bấm hai lần không tồn tại.
         */
        @Test
        @DisplayName("Thiếu Idempotency-Key: 400 IDEMPOTENCY_KEY_REQUIRED, saga không chạy")
        void thieu_idempotency_key() throws Exception {
            mvc.perform(post("/v1/orders")
                            .with(bearer(identities.customer()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

            verifyNoInteractions(placeOrder);
        }
    }

    @Nested
    @DisplayName("Đọc đơn")
    class Read {

        @Test
        @DisplayName("GET đơn: hỏi tầng application với userId của token")
        void doc_don_cua_minh() throws Exception {
            UUID user = identities.customer();
            UUID orderId = UUID.randomUUID();
            when(queries.byIdForUser(orderId, user)).thenReturn(view(orderId));

            mvc.perform(get("/v1/orders/{id}", orderId).with(bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orderId.toString()))
                    .andExpect(jsonPath("$.commissionVnd").doesNotExist())
                    .andExpect(jsonPath("$.commissionBps").doesNotExist());
        }

        @Test
        @DisplayName("GET đơn của người khác: 404 ORDER_NOT_FOUND")
        void doc_don_nguoi_khac() throws Exception {
            UUID user = identities.customer();
            UUID orderId = UUID.randomUUID();
            when(queries.byIdForUser(orderId, user))
                    .thenThrow(new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));

            mvc.perform(get("/v1/orders/{id}", orderId).with(bearer(user)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }

        @Test
        @DisplayName("orderId sai định dạng: lỗi của người gọi (4xx), không phải 500")
        void order_id_sai_dinh_dang() throws Exception {
            mvc.perform(get("/v1/orders/khong-phai-uuid").with(bearer(identities.customer())))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("Danh sách: mặc định 20/0; limit lớn bị kẹp về 100")
        void danh_sach_kep_limit() throws Exception {
            UUID user = identities.customer();

            mvc.perform(get("/v1/me/orders").with(bearer(user))).andExpect(status().isOk());
            mvc.perform(get("/v1/me/orders?limit=5000&offset=40").with(bearer(user)))
                    .andExpect(status().isOk());

            verify(queries).forUser(user, 20, 0);
            verify(queries).forUser(user, 100, 40);
        }

        /** limit/offset âm đi thẳng xuống câu SQL "LIMIT ? OFFSET ?" — PostgreSQL từ chối và khách nhận 500. */
        @Test
        @DisplayName("limit/offset âm không bao giờ xuống tới truy vấn")
        void limit_am_khong_xuong_truy_van() throws Exception {
            UUID user = identities.customer();

            mvc.perform(get("/v1/me/orders?limit=-1&offset=-5").with(bearer(user)));

            verify(queries, never()).forUser(eq(user), intThat(l -> l < 0), anyInt());
            verify(queries, never()).forUser(eq(user), anyInt(), intThat(o -> o < 0));
        }

        @Test
        @DisplayName("limit không phải số: 400")
        void limit_khong_phai_so() throws Exception {
            mvc.perform(get("/v1/me/orders?limit=abc").with(bearer(identities.customer())))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /v1/orders/{id}/cancel")
    class Cancel {

        @Test
        @DisplayName("204 và huỷ đúng đơn, đúng người theo token")
        void huy_thanh_cong() throws Exception {
            UUID user = identities.customer();
            UUID orderId = UUID.randomUUID();

            mvc.perform(post("/v1/orders/{id}/cancel", orderId).with(bearer(user)))
                    .andExpect(status().isNoContent());

            verify(closeOrder).cancel(orderId, user);
        }

        @Test
        @DisplayName("Đơn không còn mở: 409 ORDER_NOT_OPEN")
        void don_khong_mo() throws Exception {
            UUID user = identities.customer();
            UUID orderId = UUID.randomUUID();
            doThrow(new ApiException(OrderingErrorCode.ORDER_NOT_OPEN, "Order is no longer awaiting payment: PAID"))
                    .when(closeOrder)
                    .cancel(orderId, user);

            mvc.perform(post("/v1/orders/{id}/cancel", orderId).with(bearer(user)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));
        }

        @Test
        @DisplayName("Không token: 401, không huỷ gì")
        void khong_token() throws Exception {
            mvc.perform(post("/v1/orders/{id}/cancel", UUID.randomUUID())).andExpect(status().isUnauthorized());

            verifyNoInteractions(closeOrder);
        }
    }
}
