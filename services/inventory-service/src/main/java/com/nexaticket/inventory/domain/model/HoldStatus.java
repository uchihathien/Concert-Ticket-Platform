// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

/** Vòng đời một lần giữ chỗ. */
public enum HoldStatus {
    ACTIVE,

    /** Đã thành đơn hàng. Chỗ chuyển sang RESERVED, không quay lại AVAILABLE. */
    CONVERTED,

    /** Hết TTL mà không đặt hàng — worker hết hạn xử lý. */
    EXPIRED,

    /** Khách tự bỏ giữ chỗ. */
    RELEASED
}
