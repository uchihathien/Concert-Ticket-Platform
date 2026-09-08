// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

/**
 * Trạng thái saga checkout.
 *
 * <p>{@code COMPENSATION_PENDING} là trạng thái quan trọng nhất ở đây: nó nghĩa là "ta đã thay đổi
 * trạng thái ở service khác, việc bù trừ đã thử và hỏng". Không có nó thì một lần inventory-service
 * không phản hồi lúc rollback sẽ để ghế kẹt ở RESERVED mà không ai biết để dọn.
 */
public enum SagaStatus {
    STARTED,
    COMPLETED,

    /** Đã bù trừ xong, không còn gì kẹt ở service khác. */
    COMPENSATED,

    /** Bù trừ hỏng — job quét mỗi 30 giây sẽ thử lại. */
    COMPENSATION_PENDING,

    /** Hỏng trước khi kịp thay đổi gì ở service khác; không có gì để bù trừ. */
    FAILED
}
