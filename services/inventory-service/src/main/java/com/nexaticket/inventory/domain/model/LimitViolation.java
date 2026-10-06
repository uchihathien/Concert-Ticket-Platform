// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

/**
 * Kiểu vi phạm trần mua vé.
 *
 * <p>Là khái niệm nghiệp vụ, không phải mã lỗi HTTP: domain nói "vượt trần vé đứng", còn việc dịch
 * sang mã lỗi và mã trạng thái là việc của tầng application. Nhờ tách như vậy, luật trần mua vé
 * test được mà không cần dựng gì của web.
 */
public enum LimitViolation {

    /** Giữ chỗ rỗng — không ngồi cũng không đứng. */
    EMPTY_REQUEST,

    TOO_MANY_SEATED,
    TOO_MANY_STANDING,

    /** Riêng từng loại thì đạt, nhưng tổng vượt trần. */
    TOO_MANY_UNITS,

    /** Vượt trần cộng dồn của tài khoản trên suất diễn này. */
    CUSTOMER_TOTAL_EXCEEDED
}
