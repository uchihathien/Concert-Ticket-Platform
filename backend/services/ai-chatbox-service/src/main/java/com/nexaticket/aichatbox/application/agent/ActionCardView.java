// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hình dạng JSON của {@link ActionCard}: một record phẳng, phân biệt bằng {@code kind}, chỉ ghi
 * những trường khác null.
 *
 * <p>Domain cấm Jackson, mà sealed interface + record cần discriminator khi tuần tự hoá. Record phẳng
 * ở tầng application cho ra đúng ba hình dạng — {@code BOOKING_PROPOSAL} với các trường đề xuất,
 * {@code PAYMENT} với các trường đơn, {@code CHOICES} với các chip — và round-trip được qua
 * {@code ChatIdempotency} (bản lưu cũ thiếu {@code action} đọc ra {@code null}; bản lưu trước khi có
 * các trường mới đọc ra các trường ấy là {@code null}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionCardView(
        String kind,
        // BOOKING_PROPOSAL (eventTitle, sessionStartsAt dùng chung với PAYMENT)
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
        Instant paymentExpiresAt,
        String eventSlug,
        String venueName,
        List<LineView> lines,
        String status,
        // CHOICES
        String step,
        String prompt,
        List<OptionView> options) {

    /** Một dòng vé trên thẻ thanh toán. */
    public record LineView(String zoneName, int quantity, long unitPriceVnd) {}

    /** Một chip: {@code value} là câu gửi lại khi bấm. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionView(String label, String value, String hint) {}

    /** Hình dạng trước khi có thẻ CHOICES và dòng mô tả đơn — các bản lưu cũ và test cũ dựng bằng hàm này. */
    public ActionCardView(
            String kind,
            UUID id,
            String summary,
            String eventTitle,
            Instant sessionStartsAt,
            String zoneCode,
            Integer quantity,
            Long totalVnd,
            Instant expiresAt,
            UUID orderId,
            String orderNumber,
            String vietQrPayload,
            String paymentReference,
            String checkoutUrl,
            Instant paymentExpiresAt) {
        this(
                kind,
                id,
                summary,
                eventTitle,
                sessionStartsAt,
                zoneCode,
                quantity,
                totalVnd,
                expiresAt,
                orderId,
                orderNumber,
                vietQrPayload,
                paymentReference,
                checkoutUrl,
                paymentExpiresAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

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
            case ActionCard.Payment pay -> {
                ActionCard.OrderEvent event = pay.event();
                yield new ActionCardView(
                        pay.kind().name(),
                        null,
                        null,
                        event == null ? null : event.eventTitle(),
                        event == null ? null : event.sessionStartsAt(),
                        null,
                        null,
                        pay.totalVnd(),
                        null,
                        pay.orderId(),
                        pay.orderNumber(),
                        pay.vietQrPayload(),
                        pay.paymentReference(),
                        pay.checkoutUrl(),
                        pay.paymentExpiresAt(),
                        event == null ? null : event.eventSlug(),
                        event == null ? null : event.venueName(),
                        event == null
                                ? null
                                : event.lines().stream()
                                        .map(l -> new LineView(l.zoneName(), l.quantity(), l.unitPriceVnd()))
                                        .toList(),
                        pay.status(),
                        null,
                        null,
                        null);
            }
            case ActionCard.Choices c -> new ActionCardView(
                    c.kind().name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    c.step(),
                    c.prompt(),
                    c.options().stream()
                            .map(o -> new OptionView(o.label(), o.value(), o.hint()))
                            .toList());
        };
    }
}
