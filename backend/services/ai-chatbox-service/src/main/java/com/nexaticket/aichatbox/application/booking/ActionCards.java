// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Dựng thẻ hành động từ dữ liệu nghiệp vụ — một chỗ cho cả đường chat (1.3), đường nút Xác nhận và
 * đường tải lại trang (1.4), để ba đường cho ra cùng một thẻ.
 */
public final class ActionCards {

    /** Giờ Việt Nam cho dòng tóm tắt — chữ cho người đọc; các mốc {@code Instant} trong thẻ vẫn là UTC. */
    private static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' d/M/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private ActionCards() {}

    /**
     * Thẻ đề xuất. Tìm suất theo {@code eventSessionId} và hạng vé theo mã khu trong {@code event};
     * không thấy hạng vé thì dùng mã khu làm tên, không thấy suất thì bỏ phần giờ.
     */
    public static ActionCard.BookingProposal proposal(BookingProposal p, EventDetail event) {
        Optional<EventDetail.Session> session = event.sessions().stream()
                .filter(s -> p.eventSessionId().equals(s.id()))
                .findFirst();
        String tierName = session.flatMap(s -> s.tiers().stream()
                        .filter(t -> p.zoneCode().equalsIgnoreCase(t.zoneCode()))
                        .findFirst())
                .map(EventDetail.Tier::name)
                .orElse(p.zoneCode());
        Instant startsAt = session.map(EventDetail.Session::startsAt).orElse(null);
        String summary = p.quantity() + " vé " + tierName + " · " + event.title()
                + (startsAt == null ? "" : " · " + VN_TIME.format(startsAt));
        return new ActionCard.BookingProposal(
                p.id(), summary, event.title(), startsAt, p.zoneCode(), p.quantity(), p.totalVnd(), p.expiresAt());
    }

    /**
     * Thẻ thanh toán ngay sau khi tạo đơn.
     *
     * <p>TODO(1.4): {@code PlacedOrder} hôm nay chưa mang {@code vietQrPayload}/{@code paymentReference};
     * story 1.4 thêm hai trường ấy và đổi hai {@code null} dưới đây thành giá trị thật.
     */
    public static ActionCard.Payment payment(PlacedOrder order) {
        return new ActionCard.Payment(
                order.orderId(),
                order.orderNumber(),
                order.totalVnd(),
                null,
                null,
                order.checkoutUrl(),
                order.paymentExpiresAt());
    }

    /**
     * Thẻ thanh toán dựng lại từ đề xuất đã xác nhận (tải lại trang): không có QR/tham chiếu — hai
     * chuỗi ấy cố ý không lưu ở đây, giao diện lấy qua {@code GET /v1/orders/{id}}.
     *
     * @throws IllegalArgumentException đề xuất chưa có đơn
     */
    public static ActionCard.Payment payment(BookingProposal confirmed) {
        BookingProposal.Order order = confirmed.order();
        if (order == null) {
            throw new IllegalArgumentException("Đề xuất " + confirmed.id() + " chưa có đơn để dựng thẻ thanh toán");
        }
        return new ActionCard.Payment(
                order.orderId(),
                order.orderNumber(),
                confirmed.totalVnd(),
                null,
                null,
                order.checkoutUrl(),
                order.paymentExpiresAt());
    }
}
