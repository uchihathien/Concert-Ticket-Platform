// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Ranh giới "từ chối" / "chưa biết" của bước đặt đơn — thứ quyết định chỗ giữ có bị nhả (R-25). */
class OrderingHttpAdapterTest {

    private MockRestServiceServer server;
    private OrderingHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ordering");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new OrderingHttpAdapter(builder.build());
    }

    private void respond(HttpStatus status, String body) {
        var response = withStatus(status);
        if (body != null) {
            response = response.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        server.expect(requestTo("http://ordering/v1/orders"))
                .andExpect(header("Idempotency-Key", "chat-order-s1"))
                .andRespond(response);
    }

    @Test
    void request_cung_khoa_dang_chay_la_chua_biet_khong_phai_tu_choi() {
        respond(HttpStatus.CONFLICT, "{\"code\":\"REQUEST_IN_PROGRESS\",\"detail\":\"đang xử lý\"}");

        assertThatThrownBy(() -> adapter.placeOrder(UUID.randomUUID(), "token", "chat-order-s1"))
                .isInstanceOf(RemoteCallException.class)
                .isNotInstanceOf(BookingRejectedException.class);
    }

    @Test
    void conflict_ma_khac_van_la_tu_choi_co_cau_dich() {
        respond(HttpStatus.CONFLICT, "{\"code\":\"HOLD_EXPIRED\"}");

        assertThatThrownBy(() -> adapter.placeOrder(UUID.randomUUID(), "token", "chat-order-s1"))
                .isInstanceOfSatisfying(BookingRejectedException.class, e -> assertThat(e.reason())
                        .contains("hết hạn 10 phút"));
    }

    @Test
    void body_khong_phai_json_thi_tu_choi_voi_cau_chung() {
        server.expect(requestTo("http://ordering/v1/orders"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>bad gateway</html>"));

        assertThatThrownBy(() -> adapter.placeOrder(UUID.randomUUID(), "token", "chat-order-s1"))
                .isInstanceOfSatisfying(BookingRejectedException.class, e -> assertThat(e.reason())
                        .isEqualTo("Ordering không nhận đơn này."));
    }

    @Test
    void conflict_khong_co_body_thi_tu_choi_voi_cau_chung() {
        respond(HttpStatus.CONFLICT, null);

        assertThatThrownBy(() -> adapter.placeOrder(UUID.randomUUID(), "token", "chat-order-s1"))
                .isInstanceOfSatisfying(BookingRejectedException.class, e -> assertThat(e.reason())
                        .isEqualTo("Ordering không nhận đơn này."));
    }

    @Test
    void loi_5xx_la_su_co() {
        respond(HttpStatus.BAD_GATEWAY, null);

        assertThatThrownBy(() -> adapter.placeOrder(UUID.randomUUID(), "token", "chat-order-s1"))
                .isInstanceOf(RemoteCallException.class);
    }

    @Test
    void thanh_cong_thi_tra_don() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("http://ordering/v1/orders"))
                .andRespond(withSuccess(
                        "{\"orderId\":\"" + orderId + "\",\"orderNumber\":\"NT-1\",\"totalVnd\":100,"
                                + "\"checkoutUrl\":\"https://pay\",\"paymentExpiresAt\":\"2026-10-22T10:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        PlacedOrder order = adapter.placeOrder(UUID.randomUUID(), "token", "chat-order-s1");

        assertThat(order.orderId()).isEqualTo(orderId);
        assertThat(order.orderNumber()).isEqualTo("NT-1");
    }
}
