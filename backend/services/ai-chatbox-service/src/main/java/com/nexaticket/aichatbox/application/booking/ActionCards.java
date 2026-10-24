// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingDraft;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /** Thẻ thanh toán ngay sau khi tạo đơn — đủ VietQR để giao diện vẽ mã. */
    public static ActionCard.Payment payment(PlacedOrder order) {
        return new ActionCard.Payment(
                order.orderId(),
                order.orderNumber(),
                order.totalVnd(),
                order.vietQrPayload(),
                order.paymentReference(),
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

    /**
     * Sự kiện của đơn, cho dòng mô tả trên thẻ thanh toán: tên, suất, địa điểm và hạng vé — đều lấy từ
     * đề xuất đã xác nhận và bản catalog, không từ chữ của mô hình.
     */
    public static ActionCard.OrderEvent orderEvent(BookingProposal p, EventDetail event) {
        Optional<EventDetail.Session> session = event.sessions().stream()
                .filter(s -> p.eventSessionId().equals(s.id()))
                .findFirst();
        String tierName = session.flatMap(s -> s.tiers().stream()
                        .filter(t -> p.zoneCode().equalsIgnoreCase(t.zoneCode()))
                        .findFirst())
                .map(EventDetail.Tier::name)
                .orElse(p.zoneCode());
        return new ActionCard.OrderEvent(
                event.title(),
                event.slug(),
                session.map(EventDetail.Session::startsAt).orElse(null),
                event.venueName(),
                List.of(new ActionCard.OrderLine(tierName, p.quantity(), p.unitPriceVnd())));
    }

    /** Thẻ chip của bản nháp — cùng một hàm cho lượt chat và cho đường tải lại trang. */
    public static ActionCard.Choices choices(BookingDraft draft) {
        return new ActionCard.Choices(
                draft.awaiting().name(),
                draft.prompt() == null ? "" : draft.prompt(),
                draft.choices().stream()
                        .map(c -> new ActionCard.Option(c.label(), c.value(), c.hint()))
                        .toList());
    }
}
