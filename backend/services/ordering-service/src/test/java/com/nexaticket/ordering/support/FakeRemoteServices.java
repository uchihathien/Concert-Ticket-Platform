// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.support;

import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.PricingPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Hàng giả cho ba service mà saga gọi tới, <b>điều khiển được từng kiểu hỏng</b>.
 *
 * <p>Không dùng mocking framework: những gì cần ở đây là ghi lại lời gọi và bật/tắt lỗi, và một
 * lớp thường làm việc đó rõ ràng hơn một chuỗi {@code when().thenThrow()} rải khắp test.
 */
@TestConfiguration
public class FakeRemoteServices {

    @Bean
    @Primary
    public FakeInventory fakeInventory() {
        return new FakeInventory();
    }

    @Bean
    @Primary
    public FakePayment fakePayment() {
        return new FakePayment();
    }

    @Bean
    @Primary
    public FakePricing fakePricing() {
        return new FakePricing();
    }

    /** Inventory giả. Mặc định đặt chỗ thành công 2 ghế, mỗi ghế 1.500.000đ. */
    public static class FakeInventory implements InventoryPort {

        public final List<UUID> cancelledOrders = new ArrayList<>();
        public final AtomicInteger reserveCalls = new AtomicInteger();

        /** Bật để reserve() ném RemoteCallException — mô phỏng timeout. */
        public boolean reserveTimesOut;

        /** Bật để reserve() ném lỗi nghiệp vụ — Inventory từ chối dứt khoát. */
        public String refuseWithCode;

        /** Bật để cancelReservation() ném — mô phỏng bù trừ cũng hỏng. */
        public boolean cancelFails;

        public UUID eventSessionId = UUID.randomUUID();
        public UUID organizationId = UUID.randomUUID();
        public long seatPriceVnd = 1_500_000L;
        public int seatCount = 2;

        @Override
        public Reservation reserve(UUID orderId, UUID holdId, UUID userId) {
            reserveCalls.incrementAndGet();
            if (refuseWithCode != null) {
                throw new SeatsUnavailableException(refuseWithCode, "Inventory từ chối");
            }
            if (reserveTimesOut) {
                throw new RemoteCallException("inventory-service", "timeout giả lập", null);
            }
            List<Seat> seats = new ArrayList<>();
            for (int i = 1; i <= seatCount; i++) {
                seats.add(new Seat(
                        UUID.randomUUID(),
                        "A-" + i,
                        "A",
                        "SEATED",
                        String.valueOf(i),
                        UUID.randomUUID(),
                        "Ve ngoi",
                        seatPriceVnd));
            }
            return new Reservation(eventSessionId, organizationId, seats);
        }

        @Override
        public void cancelReservation(UUID orderId) {
            if (cancelFails) {
                throw new RemoteCallException("inventory-service", "bù trừ hỏng giả lập", null);
            }
            cancelledOrders.add(orderId);
        }
    }

    /** Payment giả. */
    public static class FakePayment implements PaymentPort {

        public final List<UUID> cancelledIntents = new ArrayList<>();
        public boolean openFails;

        @Override
        public Intent openIntent(UUID orderId, UUID organizationId, long totalVnd, Instant expiresAt) {
            if (openFails) {
                throw new RemoteCallException("payment-service", "timeout giả lập", null);
            }
            return new Intent(
                    "NT" + orderId.toString().substring(0, 8).toUpperCase(),
                    "00020101021238...6304ABCD",
                    "970422",
                    "0123456789");
        }

        @Override
        public void cancelIntent(UUID orderId) {
            cancelledIntents.add(orderId);
        }
    }

    /** Catalog giả. Mặc định hoa hồng 5%, không giảm giá. */
    public static class FakePricing implements PricingPort {

        public int commissionBps = 500;
        public long discountVnd;
        public boolean promotionInvalid;
        public boolean timesOut;

        @Override
        public Pricing resolve(UUID eventSessionId, String promotionCode, long subtotalVnd) {
            if (promotionInvalid) {
                throw new PromotionInvalidException("Mã khuyến mãi không hợp lệ");
            }
            if (timesOut) {
                throw new RemoteCallException("catalog-service", "timeout giả lập", null);
            }
            return new Pricing(commissionBps, discountVnd);
        }
    }
}
