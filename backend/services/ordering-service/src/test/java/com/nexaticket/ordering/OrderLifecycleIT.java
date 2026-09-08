// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ordering.application.command.CloseOrderHandler;
import com.nexaticket.ordering.application.command.ConfirmPaymentHandler;
import com.nexaticket.ordering.application.command.ExpireOrdersJob;
import com.nexaticket.ordering.application.command.PlaceOrderHandler;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.support.OrderingTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Vòng đời đơn hàng sau khi saga đã xong. */
class OrderLifecycleIT extends OrderingTestBase {

    @Autowired
    PlaceOrderHandler placeOrder;

    @Autowired
    ConfirmPaymentHandler confirmPayment;

    @Autowired
    CloseOrderHandler closeOrder;

    @Autowired
    ExpireOrdersJob expireOrders;

    @Autowired
    OrderRepository orders;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Webhook xác nhận hai lần: chỉ một sự kiện order.paid, paidAt không đổi")
    void xac_nhan_thanh_toan_idempotent() {
        var order = newOrder();

        confirmPayment.handle(order);
        var firstPaidAt = orders.findById(order).orElseThrow().paidAt();
        confirmPayment.handle(order);

        var reloaded = orders.findById(order).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OrderStatus.PAID);
        assertThat(reloaded.paidAt()).isEqualTo(firstPaidAt);
        // Bắn hai lần thì ticketing-service sẽ phát vé đôi.
        assertThat(outboxCount(order, "order.paid")).isEqualTo(1);
    }

    @Test
    @DisplayName("Worker hết hạn KHÔNG được đóng đơn đã thanh toán")
    void worker_khong_dong_don_da_thanh_toan() {
        var order = newOrder();
        confirmPayment.handle(order);
        expireNow(order);

        expireOrders.runOnce();

        // Đóng một đơn đã nhận tiền nghĩa là khách mất tiền và mất vé cùng lúc.
        assertThat(orders.findById(order).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("Đơn quá hạn chuyển khoản bị đóng và bắn order.expired")
    void don_qua_han_bi_dong() {
        var order = newOrder();
        expireNow(order);

        assertThat(expireOrders.runOnce()).isEqualTo(1);

        assertThat(orders.findById(order).orElseThrow().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(outboxCount(order, "order.expired")).isEqualTo(1);
    }

    @Test
    @DisplayName("Khách huỷ đơn: đóng đơn và nhả chỗ ngay, không chờ outbox")
    void khach_huy_don() {
        UUID user = UUID.randomUUID();
        var result = placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), user, null));

        closeOrder.cancel(result.orderId(), user);

        assertThat(orders.findById(result.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxCount(result.orderId(), "order.cancelled")).isEqualTo(1);
    }

    @Test
    @DisplayName("Huỷ đơn của người khác trả 404, không phải 403 — không xác nhận đơn có thật")
    void khong_huy_duoc_don_nguoi_khac() {
        var result = placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), null));

        assertThat(codeOf(() -> closeOrder.cancel(result.orderId(), UUID.randomUUID())))
                .isEqualTo("ORDER_NOT_FOUND");
    }

    private UUID newOrder() {
        return placeOrder
                .handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), null))
                .orderId();
    }

    private void expireNow(UUID orderId) {
        jdbc.update("UPDATE orders SET payment_expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
    }

    private int outboxCount(UUID orderId, String eventType) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                orderId,
                eventType);
        return n == null ? 0 : n;
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (com.nexaticket.platform.web.error.ApiException e) {
            return e.errorCode().code();
        }
    }
}
