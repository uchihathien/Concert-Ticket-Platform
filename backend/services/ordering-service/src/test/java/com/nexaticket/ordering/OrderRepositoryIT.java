// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ordering.application.command.ConfirmPaymentHandler;
import com.nexaticket.ordering.application.command.PlaceOrderHandler;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.support.FakeRemoteServices;
import com.nexaticket.ordering.support.OrderingTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Các đường đọc của kho đơn hàng trên PostgreSQL thật. Mọi đơn dựng mới với holdId/userId ngẫu
 * nhiên — container được dùng lại qua nhiều lần chạy nên dữ liệu cũ vẫn còn đó.
 */
class OrderRepositoryIT extends OrderingTestBase {

    @Autowired
    PlaceOrderHandler placeOrder;

    @Autowired
    ConfirmPaymentHandler confirmPayment;

    @Autowired
    OrderRepository orders;

    @Autowired
    FakeRemoteServices.FakeInventory inventory;

    @Autowired
    FakeRemoteServices.FakePayment payment;

    @Autowired
    FakeRemoteServices.FakePricing pricing;

    /** Context Spring được cache giữa các lớp IT: lớp chạy trước có thể để fake ở trạng thái hỏng. */
    @BeforeEach
    void resetFakes() {
        inventory.reserveTimesOut = false;
        inventory.refuseWithCode = null;
        inventory.cancelFails = false;
        inventory.seatCount = 2;
        inventory.seatPriceVnd = 1_500_000L;
        payment.openFails = false;
        pricing.discountVnd = 0;
        pricing.promotionInvalid = false;
        pricing.timesOut = false;
    }

    @Test
    @DisplayName("Trạng thái hàng loạt: đúng từng đơn, đơn không tồn tại vắng mặt khỏi map")
    void trang_thai_hang_loat() {
        UUID paid = newOrder(UUID.randomUUID());
        UUID awaiting = newOrder(UUID.randomUUID());
        UUID missing = UUID.randomUUID();
        confirmPayment.handle(paid);

        Map<UUID, String> statuses = orders.statusesOf(List.of(paid, awaiting, missing));

        assertThat(statuses)
                .containsEntry(paid, "PAID")
                .containsEntry(awaiting, "AWAITING_PAYMENT")
                .doesNotContainKey(missing)
                .hasSize(2);
    }

    @Test
    @DisplayName("Đơn của tôi: chỉ đơn của đúng người đó, phân trang không trùng không sót")
    void don_cua_toi_phan_trang() {
        UUID me = UUID.randomUUID();
        UUID a = newOrder(me);
        UUID b = newOrder(me);
        UUID c = newOrder(me);
        UUID someoneElse = newOrder(UUID.randomUUID());

        List<UUID> page1 = orders.findByUser(me, 2, 0).stream().map(Order::id).toList();
        List<UUID> page2 = orders.findByUser(me, 2, 2).stream().map(Order::id).toList();

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(1);
        assertThat(concat(page1, page2)).containsExactlyInAnyOrder(a, b, c).doesNotContain(someoneElse);
    }

    @Test
    @DisplayName("Tra theo nội dung chuyển khoản trả lại đúng snapshot link thanh toán đã lưu")
    void tra_theo_noi_dung_chuyen_khoan() {
        PlaceOrderHandler.Result placed =
                placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), null));

        Order found = orders.findByPaymentReference(placed.paymentReference()).orElseThrow();

        assertThat(found.id()).isEqualTo(placed.orderId());
        assertThat(found.checkoutUrl()).isEqualTo(placed.checkoutUrl());
        assertThat(found.vietQrPayload()).isEqualTo(placed.vietQrPayload());
        assertThat(found.items()).hasSize(2);
    }

    private UUID newOrder(UUID userId) {
        return placeOrder
                .handle(new PlaceOrderHandler.Command(UUID.randomUUID(), userId, null))
                .orderId();
    }

    private static List<UUID> concat(List<UUID> first, List<UUID> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
