// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
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
public sealed interface ActionCard permits ActionCard.BookingProposal, ActionCard.Payment {

    /** {@code BOOKING_INTENT} chỉ khai sẵn cho Phase 2 (ghi nhớ ý định mua), chưa có biến thể. */
    enum Kind {
        BOOKING_PROPOSAL,
        PAYMENT,
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
     */
    record Payment(
            UUID orderId,
            String orderNumber,
            long totalVnd,
            String vietQrPayload,
            String paymentReference,
            String checkoutUrl,
            Instant paymentExpiresAt)
            implements ActionCard {

        public Payment {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(orderNumber, "orderNumber");
        }

        @Override
        public Kind kind() {
            return Kind.PAYMENT;
        }
    }
}
