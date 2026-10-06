// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ordering.application.command.CloseOrderHandler;
import com.nexaticket.ordering.application.command.ConfirmPaymentHandler;
import com.nexaticket.ordering.application.command.ExpireOrdersJob;
import com.nexaticket.ordering.application.command.PlaceOrderHandler;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.support.FakeRemoteServices;
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
    FakeRemoteServices.FakeInventory inventory;

    @Autowired
    FakeRemoteServices.FakePayment payments;

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
        // Và tuyệt đối không được nhả chỗ của nó: khách đã trả tiền vẫn phải có ghế.
        assertThat(inventory.cancelledOrders).doesNotContain(order);
    }

    @Test
    @DisplayName("Đơn quá hạn chuyển khoản bị đóng và bắn order.expired")
    void don_qua_han_bi_dong() {
        var order = newOrder();
        expireNow(order);

        assertThat(expireOrders.runOnce()).isEqualTo(1);

        assertThat(orders.findById(order).orElseThrow().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(outboxCount(order, "order.expired")).isEqualTo(1);
        // Link thanh toán phải đóng theo đơn, nếu không nó vẫn nhận được tiền cho tới expiredAt.
        assertThat(payments.cancelledIntents).contains(order);

        // Chốt chặn cho lỗi rò rỉ tồn kho: lần giữ chỗ đã CONVERTED nên ExpireHoldsJob bên
        // Inventory không đụng tới nó nữa. Không nhả ở đây thì chỗ kẹt ở RESERVED vĩnh viễn,
        // và khách bị tính vào trần mua vé của suất đó mãi mãi.
        assertThat(inventory.cancelledOrders).contains(order);
    }

    @Test
    @DisplayName("Tiền vào sau khi đơn hết hạn: MANUAL_REVIEW, không PAID và không phát vé")
    void tien_vao_sau_khi_don_het_han() {
        var order = newOrder();
        expireNow(order);
        expireOrders.runOnce();

        // Khách chuyển khoản ở phút chót; webhook tới sau khi job vừa đóng đơn.
        assertThat(confirmPayment.handle(order)).isEqualTo(ConfirmPaymentHandler.Outcome.MANUAL_REVIEW);

        var reloaded = orders.findById(order).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OrderStatus.MANUAL_REVIEW);
        // Ghế đã nhả lúc đóng đơn và có thể đã bán cho người khác — phát vé ở đây là hai người
        // cùng một chỗ. Không có order.paid nghĩa là ticketing không phát gì.
        assertThat(outboxCount(order, "order.paid")).isZero();
        // Và paidAt vẫn trống: mốc đó đi vào sổ cái, một đơn chưa được công nhận đã trả tiền thì
        // không được mang nó.
        assertThat(reloaded.paidAt()).isNull();
    }

    @Test
    @DisplayName("Xác nhận lần hai trên đơn đã MANUAL_REVIEW vẫn trả lời dứt khoát, không ném")
    void xac_nhan_trung_tren_don_dang_doi_soat() {
        var order = newOrder();
        expireNow(order);
        expireOrders.runOnce();
        confirmPayment.handle(order);

        // payOS giao lại webhook cho tới khi nhận 2xx. Ném ở đây là vòng lặp vô tận.
        assertThat(confirmPayment.handle(order)).isEqualTo(ConfirmPaymentHandler.Outcome.MANUAL_REVIEW);
        assertThat(orders.findById(order).orElseThrow().status()).isEqualTo(OrderStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Khách huỷ đơn: đóng link thanh toán và nhả chỗ ngay, không chờ outbox")
    void khach_huy_don() {
        UUID user = UUID.randomUUID();
        var result = placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), user, null));

        closeOrder.cancel(result.orderId(), user);

        assertThat(orders.findById(result.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxCount(result.orderId(), "order.cancelled")).isEqualTo(1);
        assertThat(inventory.cancelledOrders).contains(result.orderId());
        // Link payOS sống tới hết hạn thanh toán ban đầu. Không đóng nó thì khách mở lại tab cũ,
        // chuyển tiền, và tiền vào thật một đơn đã huỷ với ghế đã bán cho người khác.
        assertThat(payments.cancelledIntents).contains(result.orderId());
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
