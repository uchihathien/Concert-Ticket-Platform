// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.support;

import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentReference;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.model.PayosLink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Dựng {@link PaymentIntent} ở đúng trạng thái cần cho unit test của tầng application.
 *
 * <p>Trạng thái khác PENDING đi qua {@code rehydrate} — đúng con đường repository thật dùng — chứ không
 * gọi {@code confirm}/{@code cancel} để "đi tới" trạng thái đó: một test của handler không nên phụ thuộc
 * vào việc các chuyển trạng thái của domain còn đúng hay không.
 */
public final class IntentFixtures {

    public static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final long AMOUNT = 3_000_000L;
    public static final long ORDER_CODE = 1_000_001L;

    private IntentFixtures() {}

    public static PayosLink link(long orderCode, long amountVnd) {
        return new PayosLink(
                orderCode,
                "link-" + orderCode,
                "https://pay.payos.vn/web/link-" + orderCode,
                "00020101021238570010A000000727012700069704220113V3CAS" + orderCode + "0208QRIBFTTA5303704",
                "970422",
                "V3CAS" + orderCode,
                "NEXATICKET",
                amountVnd,
                "PENDING");
    }

    public static PaymentIntent pending(UUID orderId, long orderCode) {
        return PaymentIntent.open(
                orderId,
                UUID.randomUUID(),
                AMOUNT,
                NOW.plus(Duration.ofMinutes(15)),
                PaymentReference.forOrderCode(orderCode),
                link(orderCode, AMOUNT));
    }

    public static PaymentIntent confirmed(UUID orderId, long orderCode, String providerTxnId) {
        return rehydrate(orderId, orderCode, PaymentStatus.CONFIRMED, "PAYOS", providerTxnId, AMOUNT, NOW, null);
    }

    public static PaymentIntent closed(UUID orderId, long orderCode, PaymentStatus status) {
        return rehydrate(orderId, orderCode, status, null, null, null, null, NOW);
    }

    /** Intent thời SePay: không có link payOS nào, {@code payos_order_code} là NULL trong database. */
    public static PaymentIntent legacy(UUID orderId) {
        return PaymentIntent.rehydrate(
                orderId,
                UUID.randomUUID(),
                "NT0000042",
                AMOUNT,
                "000201010212legacy",
                "970422",
                "0123456789",
                "NEXATICKET",
                PaymentIntent.NO_PAYOS_LINK,
                null,
                null,
                NOW.plus(Duration.ofMinutes(15)),
                PaymentStatus.PENDING,
                null,
                null,
                null,
                null,
                null);
    }

    @SuppressWarnings("java:S107")
    private static PaymentIntent rehydrate(
            UUID orderId,
            long orderCode,
            PaymentStatus status,
            String provider,
            String providerTxnId,
            Long paidAmountVnd,
            Instant confirmedAt,
            Instant cancelledAt) {
        PayosLink link = link(orderCode, AMOUNT);
        return PaymentIntent.rehydrate(
                orderId,
                UUID.randomUUID(),
                PaymentReference.forOrderCode(orderCode).value(),
                AMOUNT,
                link.qrCode(),
                link.bin(),
                link.accountNumber(),
                link.accountName(),
                orderCode,
                link.paymentLinkId(),
                link.checkoutUrl(),
                NOW.plus(Duration.ofMinutes(15)),
                status,
                provider,
                providerTxnId,
                paidAmountVnd,
                confirmedAt,
                cancelledAt);
    }
}
