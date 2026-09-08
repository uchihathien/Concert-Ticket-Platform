// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

/**
 * Vòng đời đơn hàng.
 *
 * <pre>
 *   AWAITING_PAYMENT ──webhook xác nhận──▶ PAID ──hoàn tiền──▶ REFUNDED
 *          │
 *          ├──quá payment_expires_at──▶ EXPIRED
 *          └──khách huỷ──────────────▶ CANCELLED
 * </pre>
 *
 * <p>{@code EXPIRED} và {@code CANCELLED} khác nhau ở chỗ ai kết thúc đơn — quan trọng khi đối
 * soát và khi trả lời khiếu nại "tôi có bấm huỷ đâu".
 */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    EXPIRED,
    CANCELLED,
    REFUNDED;

    /** Chỉ đơn đang chờ tiền mới đóng lại được. Đơn đã PAID phải đi đường hoàn tiền. */
    public boolean isOpen() {
        return this == AWAITING_PAYMENT;
    }
}
