// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.support;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderItem;
import com.nexaticket.ordering.domain.model.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dựng đơn hàng ở bất kỳ trạng thái nào cho unit test — không cần database. */
public final class OrderFixtures {

    public static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private OrderFixtures() {}

    public static OrderItem item(long unitPriceVnd, long discountVnd, int commissionBps) {
        return new OrderItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "A-1",
                "A",
                "SEATED",
                "1",
                UUID.randomUUID(),
                "Ve ngoi",
                Money.ofVnd(unitPriceVnd),
                Money.ofVnd(discountVnd),
                commissionBps);
    }

    /** Đơn mới chờ thanh toán của {@code userId}: hai vé 500.000đ, hoa hồng 5%. */
    public static Order awaiting(UUID userId) {
        return Order.awaitingPayment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                List.of(item(500_000, 0, 500), item(500_000, 0, 500)),
                null,
                NOW,
                WINDOW);
    }

    /** Đơn đọc lại từ database ở trạng thái {@code status}. */
    public static Order inStatus(OrderStatus status, UUID userId) {
        List<OrderItem> items = new ArrayList<>(List.of(item(500_000, 100_000, 500), item(300_000, 0, 500)));
        return Order.rehydrate(
                UUID.randomUUID(),
                "NT-260908-K7M2QP",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                items,
                "SALE10",
                NOW.plus(WINDOW),
                status,
                "NT1000001",
                "000201...6304ABCD",
                "https://pay.payos.vn/web/1000001",
                // Đúng ràng buộc ck_paid_has_timestamp: chỉ PAID mới mang paid_at.
                status == OrderStatus.PAID ? NOW.plusSeconds(60) : null);
    }
}
