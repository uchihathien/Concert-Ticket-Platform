// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

/**
 * Vòng đời đơn hàng.
 *
 * <pre>
 *   AWAITING_PAYMENT ──webhook xác nhận──▶ PAID ──hoàn tiền──▶ REFUNDED
 *          │
 *          ├──quá payment_expires_at──▶ EXPIRED ──┐
 *          └──khách huỷ──────────────▶ CANCELLED ─┴──tiền vào muộn──▶ MANUAL_REVIEW
 * </pre>
 *
 * <p>{@code EXPIRED} và {@code CANCELLED} khác nhau ở chỗ ai kết thúc đơn — quan trọng khi đối
 * soát và khi trả lời khiếu nại "tôi có bấm huỷ đâu".
 *
 * <p>{@code MANUAL_REVIEW} là chỗ đậu của <b>tiền thật vào một đơn đã đóng</b>: khách chuyển khoản
 * ở phút chót và webhook tới sau khi job hết hạn vừa đóng đơn. Không thể chuyển sang PAID — ghế đã
 * nhả và có thể đã bán cho người khác, phát vé ở đây là hai người cùng một chỗ. Cũng không thể im
 * lặng bỏ qua — tiền đã nằm trong tài khoản ta. Nên nó là một trạng thái riêng, và nó tồn tại để
 * một con người nhìn thấy rồi quyết định hoàn tiền hay xếp lại chỗ.
 */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    MANUAL_REVIEW;

    /** Chỉ đơn đang chờ tiền mới đóng lại được. Đơn đã PAID phải đi đường hoàn tiền. */
    public boolean isOpen() {
        return this == AWAITING_PAYMENT;
    }
}
