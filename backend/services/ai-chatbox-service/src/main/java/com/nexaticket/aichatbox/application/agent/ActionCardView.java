// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import java.time.Instant;
import java.util.UUID;

/**
 * Hình dạng JSON của {@link ActionCard}: một record phẳng, phân biệt bằng {@code kind}, chỉ ghi
 * những trường khác null.
 *
 * <p>Domain cấm Jackson, mà sealed interface + record cần discriminator khi tuần tự hoá. Record phẳng
 * ở tầng application cho ra đúng hai hình dạng — {@code BOOKING_PROPOSAL} với các trường đề xuất,
 * {@code PAYMENT} với các trường đơn — và round-trip được qua {@code ChatIdempotency} (bản lưu cũ
 * thiếu {@code action} đọc ra {@code null}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionCardView(
        String kind,
        // BOOKING_PROPOSAL
        UUID id,
        String summary,
        String eventTitle,
        Instant sessionStartsAt,
        String zoneCode,
        Integer quantity,
        Long totalVnd,
        Instant expiresAt,
        // PAYMENT
        UUID orderId,
        String orderNumber,
        String vietQrPayload,
        String paymentReference,
        String checkoutUrl,
        Instant paymentExpiresAt) {

    /** {@code null} → {@code null}: controller ghi {@code "action": null} khi lượt không có thẻ. */
    public static ActionCardView of(ActionCard card) {
        if (card == null) {
            return null;
        }
        return switch (card) {
            case ActionCard.BookingProposal p -> new ActionCardView(
                    p.kind().name(),
                    p.id(),
                    p.summary(),
                    p.eventTitle(),
                    p.sessionStartsAt(),
                    p.zoneCode(),
                    p.quantity(),
                    p.totalVnd(),
                    p.expiresAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            case ActionCard.Payment pay -> new ActionCardView(
                    pay.kind().name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    pay.totalVnd(),
                    null,
                    pay.orderId(),
                    pay.orderNumber(),
                    pay.vietQrPayload(),
                    pay.paymentReference(),
                    pay.checkoutUrl(),
                    pay.paymentExpiresAt());
        };
    }
}
