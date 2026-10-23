// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Đề xuất đặt vé do trợ lý lập, chờ khách xác nhận (ADR-2001).
 *
 * <p>Trợ lý <b>không</b> đặt vé. Nó lập đề xuất này — đã kiểm sự kiện, suất, khu, tính tổng tiền —
 * và khách bấm "Đồng ý" trên thẻ thì endpoint xác nhận mới giữ chỗ, tạo đơn. Vì vậy record này mang
 * đủ dữ liệu để dựng lại thẻ và để kiểm "còn đúng giá không" lúc xác nhận, cộng {@code userId} để
 * endpoint xác nhận đối chiếu với JWT.
 *
 * @param order đơn đã tạo lúc xác nhận — {@code null} khi chưa/không xác nhận. Chép từ
 *     {@code PlacedOrder} vì ordering không trả lại {@code checkoutUrl} ở đường tra đơn.
 * @param expiresAt hết hiệu lực; quá mốc này mà chưa bấm thì job chuyển sang EXPIRED
 */
public record BookingProposal(
        UUID id,
        UUID userId,
        UUID chatSessionId,
        String eventSlug,
        UUID eventSessionId,
        String zoneCode,
        int quantity,
        long unitPriceVnd,
        long totalVnd,
        BookingProposalStatus status,
        Order order,
        Instant expiresAt,
        Instant createdAt,
        Instant confirmedAt) {

    /** Bốn trường của {@code PlacedOrder} ngoài {@code totalVnd} (đã có ở cấp đề xuất). */
    public record Order(UUID orderId, String orderNumber, String checkoutUrl, Instant paymentExpiresAt) {
        public Order {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(orderNumber, "orderNumber");
        }
    }

    public BookingProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(chatSessionId, "chatSessionId");
        Objects.requireNonNull(eventSlug, "eventSlug");
        Objects.requireNonNull(eventSessionId, "eventSessionId");
        Objects.requireNonNull(zoneCode, "zoneCode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (quantity < 1) {
            throw new IllegalArgumentException("Số vé phải từ 1: " + quantity);
        }
        if (unitPriceVnd < 0) {
            throw new IllegalArgumentException("Đơn giá âm: " + unitPriceVnd);
        }
        if (totalVnd != unitPriceVnd * quantity) {
            throw new IllegalArgumentException(
                    "Tổng tiền %d không bằng đơn giá %d × %d vé".formatted(totalVnd, unitPriceVnd, quantity));
        }
    }

    /** Còn bấm được: đang PROPOSED và chưa quá hạn. Hai điều kiện, vì job hết hạn chạy theo lịch, không tức thì. */
    public boolean isOpen(Instant now) {
        return status == BookingProposalStatus.PROPOSED && expiresAt.isAfter(now);
    }

    /**
     * Đề xuất mới, hiệu lực {@code ttl} kể từ {@code now}.
     *
     * <p>Tổng tiền và hạn tính ở đây, một chỗ, để tool lập đề xuất (1.2) không tự cộng và không tự
     * chọn mốc hết hạn.
     */
    public static BookingProposal proposed(
            UUID id,
            UUID userId,
            UUID chatSessionId,
            String eventSlug,
            UUID eventSessionId,
            String zoneCode,
            int quantity,
            long unitPriceVnd,
            Instant now,
            Duration ttl) {
        return new BookingProposal(
                id,
                userId,
                chatSessionId,
                eventSlug,
                eventSessionId,
                zoneCode,
                quantity,
                unitPriceVnd,
                unitPriceVnd * quantity,
                BookingProposalStatus.PROPOSED,
                null,
                now.plus(ttl),
                now,
                null);
    }
}
