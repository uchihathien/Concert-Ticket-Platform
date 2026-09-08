// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

/**
 * Kết luận cho một giao dịch ngân hàng.
 *
 * <p><b>Nguyên tắc chốt (BRAINSTORM §4.2):</b> {@link #REJECTED} chỉ dùng khi <b>chắc chắn không
 * có đồng nào</b> về tài khoản của ta. Có tiền mà không khớp thì luôn là {@link #MANUAL_REVIEW}.
 *
 * <p>Lý do không phải kỹ thuật mà là kế toán: tiền đã nằm trong tài khoản ký quỹ là tiền của một
 * người thật. Đánh {@code REJECTED} nghĩa là "không ai xử lý nữa", và kết quả là một giao dịch vô
 * chủ — thứ mà runbook đối soát lấy làm tiêu chí thất bại.
 */
public enum WebhookOutcome {

    /** Khớp đơn, đủ tiền, còn hạn. Đơn chuyển sang PAID và vé được phát. */
    CONFIRMED,

    /** Đã xử lý transaction này rồi. Trả 2xx để SePay ngừng retry. */
    DUPLICATE,

    /** Có tiền nhưng không khớp hoàn toàn. Vào danh sách việc của người đối soát. */
    MANUAL_REVIEW,

    /** Chắc chắn không có tiền về tài khoản ta — chỉ dùng khi thật sự chắc chắn. */
    REJECTED
}
