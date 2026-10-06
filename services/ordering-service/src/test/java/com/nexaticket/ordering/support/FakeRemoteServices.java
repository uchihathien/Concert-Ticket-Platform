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
        public UUID eventId = UUID.randomUUID();
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
            return new Reservation(eventSessionId, eventId, organizationId, seats);
        }

        @Override
        public void cancelReservation(UUID orderId) {
            if (cancelFails) {
                throw new RemoteCallException("inventory-service", "bù trừ hỏng giả lập", null);
            }
            cancelledOrders.add(orderId);
        }
    }

    /**
     * Payment giả.
     *
     * <p><b>Idempotent theo orderId</b>, y như bản thật, và đó không phải chi tiết trang trí: saga gọi lại
     * bước mở intent sau timeout mạng, và test về tính idempotent của checkout chỉ có nghĩa khi fake cũng
     * trả về đúng link của lần đầu. Một fake sinh giá trị mới mỗi lần gọi sẽ làm test đó xanh vì lý do sai.
     */
    public static class FakePayment implements PaymentPort {

        public final List<UUID> cancelledIntents = new ArrayList<>();
        public boolean openFails;

        /**
         * Mô phỏng sequence {@code payment_order_code_seq}: trong dải 7 chữ số để reference luôn đúng 9
         * ký tự, đúng trần trường {@code description} của payOS.
         *
         * <p><b>Điểm bắt đầu là ngẫu nhiên, không phải 1.000.000.</b> Container PostgreSQL của test
         * {@code withReuse(true)} nên nó sống qua nhiều lần build và <b>giữ lại dữ liệu cũ</b>, còn
         * {@code orders.payment_reference} là {@code UNIQUE}. Một fake đếm lại từ cùng một số ở mỗi JVM
         * sẽ trùng reference với những đơn của lần build trước — và triệu chứng gây hiểu nhầm hết sức:
         * {@code JdbcOrderRepository.save} bắt mọi {@code DuplicateKeyException} rồi báo thành
         * {@code DuplicateHoldException}, nên lỗi hiện ra là "đơn trùng cho cùng một lần giữ chỗ" ở một
         * test không hề dùng lại holdId nào.
         *
         * <p>Tính đơn điệu không quan trọng ở đây; tính KHÔNG TRÙNG mới quan trọng — y như với sequence thật.
         */
        private final java.util.concurrent.atomic.AtomicLong nextOrderCode = new java.util.concurrent.atomic.AtomicLong(
                java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000L, 9_000_000L));

        private final java.util.Map<UUID, Intent> opened = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Intent openIntent(UUID orderId, UUID organizationId, long totalVnd, Instant expiresAt) {
            if (openFails) {
                throw new RemoteCallException("payment-service", "timeout giả lập", null);
            }
            return opened.computeIfAbsent(orderId, id -> {
                long orderCode = nextOrderCode.getAndIncrement();
                // Tài khoản ảo RIÊNG cho từng link, đúng như payOS cấp — không phải một tài khoản ký quỹ
                // dùng chung. Dùng chung một số ở fake sẽ che mất mọi lỗi gắn sai tài khoản vào đơn.
                return new Intent(
                        "NT" + orderCode,
                        "00020101021238...6304ABCD",
                        "https://pay.payos.vn/web/" + orderCode,
                        "970422",
                        "V3CAS" + orderCode,
                        "NEXATICKET");
            });
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
