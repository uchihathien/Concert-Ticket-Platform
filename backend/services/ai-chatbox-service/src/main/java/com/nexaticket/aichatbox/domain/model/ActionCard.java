// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Thẻ hành động đi kèm câu trả lời — dữ liệu có cấu trúc để giao diện vẽ nút, KHÔNG phải chữ để
 * mô hình đọc (ADR-2001).
 *
 * <p>Hai thứ cố ý tách khỏi {@code ToolOutcome.payload}: giao diện không bao giờ phải parse
 * {@code answer} để tìm "đề xuất", và chuỗi VietQR không bao giờ đi qua mô hình — một mô hình chép
 * sai một ký tự là tiền đi lạc. Thẻ đi theo kênh có kiểu từ dispatcher lên controller; JSON ra ngoài
 * là {@code ActionCardView} ở tầng application (domain không có Jackson).
 */
public sealed interface ActionCard permits ActionCard.BookingProposal, ActionCard.Payment, ActionCard.Choices {

    /** {@code BOOKING_INTENT} chỉ khai sẵn cho Phase 2 (ghi nhớ ý định mua), chưa có biến thể. */
    enum Kind {
        BOOKING_PROPOSAL,
        PAYMENT,
        CHOICES,
        BOOKING_INTENT
    }

    Kind kind();

    /**
     * Đề xuất đặt vé chờ khách bấm Xác nhận/Huỷ.
     *
     * @param summary một dòng cho người đọc, vd. "2 vé VIP · Đêm nhạc Trịnh · 20:00 ngày 20/11/2026"
     * @param sessionStartsAt {@code null} khi catalog không có giờ diễn
     */
    record BookingProposal(
            UUID id,
            String summary,
            String eventTitle,
            Instant sessionStartsAt,
            String zoneCode,
            int quantity,
            long totalVnd,
            Instant expiresAt)
            implements ActionCard {

        public BookingProposal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(eventTitle, "eventTitle");
            Objects.requireNonNull(zoneCode, "zoneCode");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public Kind kind() {
            return Kind.BOOKING_PROPOSAL;
        }
    }

    /**
     * Đơn vừa tạo, chờ thanh toán.
     *
     * @param vietQrPayload chuỗi VietQR để vẽ mã — {@code null} khi thẻ dựng lại từ dữ liệu đã lưu
     *     (giao diện lấy lại qua {@code GET /v1/orders/{id}})
     * @param checkoutUrl đường dự phòng khi không quét được
     * @param event sự kiện và hạng vé của đơn — {@code null} khi catalog không trả lời lúc dựng thẻ;
     *     thẻ vẫn thanh toán được, chỉ thiếu dòng mô tả
     * @param status trạng thái đơn lúc dựng thẻ ({@code AWAITING_PAYMENT}, {@code PAID}, …) —
     *     {@code null} nghĩa là "vừa tạo, chưa tra lại". Giao diện vẫn tự hỏi lại đơn; trường này để
     *     câu trả lời "đơn của bạn đã thanh toán" và thẻ đi kèm nói cùng một điều ngay từ đầu.
     */
    record Payment(
            UUID orderId,
            String orderNumber,
            long totalVnd,
            String vietQrPayload,
            String paymentReference,
            String checkoutUrl,
            Instant paymentExpiresAt,
            OrderEvent event,
            String status)
            implements ActionCard {

        public Payment {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(orderNumber, "orderNumber");
        }

        public Payment(
                UUID orderId,
                String orderNumber,
                long totalVnd,
                String vietQrPayload,
                String paymentReference,
                String checkoutUrl,
                Instant paymentExpiresAt) {
            this(
                    orderId,
                    orderNumber,
                    totalVnd,
                    vietQrPayload,
                    paymentReference,
                    checkoutUrl,
                    paymentExpiresAt,
                    null,
                    null);
        }

        public Payment withEvent(OrderEvent orderEvent) {
            return new Payment(
                    orderId,
                    orderNumber,
                    totalVnd,
                    vietQrPayload,
                    paymentReference,
                    checkoutUrl,
                    paymentExpiresAt,
                    orderEvent,
                    status);
        }

        public Payment withStatus(String newStatus) {
            return new Payment(
                    orderId,
                    orderNumber,
                    totalVnd,
                    vietQrPayload,
                    paymentReference,
                    checkoutUrl,
                    paymentExpiresAt,
                    event,
                    newStatus);
        }

        @Override
        public Kind kind() {
            return Kind.PAYMENT;
        }
    }

    /**
     * Sự kiện của một đơn, cho dòng mô tả trên thẻ thanh toán.
     *
     * @param sessionStartsAt {@code null} khi catalog không có giờ diễn
     */
    record OrderEvent(
            String eventTitle, String eventSlug, Instant sessionStartsAt, String venueName, List<OrderLine> lines) {

        public OrderEvent {
            Objects.requireNonNull(eventTitle, "eventTitle");
            Objects.requireNonNull(eventSlug, "eventSlug");
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Một dòng vé của đơn: "2 × VIP, 1.500.000đ mỗi vé". */
    record OrderLine(String zoneName, int quantity, long unitPriceVnd) {}

    /**
     * Các lựa chọn nhanh khi trợ lý cần khách chọn: sự kiện, suất diễn, khu, số vé.
     *
     * <p>Mọi chip dựng từ dữ liệu catalog/inventory vừa tra — không chip nào do mô hình viết. Bấm chip là
     * gửi {@code value} như một tin nhắn; server đối chiếu nó với đúng danh sách chip đã lưu (V0111) để
     * biết khách chọn gì, không đoán ngược từ chữ.
     *
     * @param step bước đang hỏi: {@code EVENT}, {@code SESSION}, {@code ZONE}, {@code QUANTITY}
     */
    record Choices(String step, String prompt, List<Option> options) implements ActionCard {

        public Choices {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(prompt, "prompt");
            options = options == null ? List.of() : List.copyOf(options);
        }

        @Override
        public Kind kind() {
            return Kind.CHOICES;
        }
    }

    /**
     * Một chip.
     *
     * @param hint dòng phụ dưới nhãn, vd. "còn 12 chỗ" — {@code null} khi không có gì thêm
     */
    record Option(String label, String value, String hint) {

        public Option {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }
}
