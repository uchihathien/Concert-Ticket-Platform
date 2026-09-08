// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application;

import com.nexaticket.inventory.domain.model.LimitViolation;
import com.nexaticket.platform.web.error.ErrorCode;

/**
 * Mã lỗi nghiệp vụ của inventory. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành.
 *
 * <p>web-customer rẽ nhánh giao diện theo các mã này: {@code SEAT_UNAVAILABLE} thì refetch sơ đồ và
 * cho chọn lại, {@code CUSTOMER_LIMIT_EXCEEDED} thì hiện số vé còn được mua.
 */
public enum InventoryErrorCode implements ErrorCode {
    SESSION_NOT_FOUND(404),

    /** Ngoài khung giờ mở bán, kể cả khi còn chỗ trống. */
    SALES_CLOSED(409),

    /** Ít nhất một chỗ vừa bị người khác giữ. */
    SEAT_UNAVAILABLE(409),

    /** Zone vé đứng không còn đủ số lượng yêu cầu. */
    ZONE_SOLD_OUT(409),

    /** Vượt trần mỗi lần giữ chỗ (ngồi / đứng / tổng). */
    HOLD_LIMIT_EXCEEDED(400),

    /** Vượt trần cộng dồn mỗi tài khoản trên suất diễn này. */
    CUSTOMER_LIMIT_EXCEEDED(409),

    HOLD_NOT_FOUND(404),

    /** Giữ chỗ của người khác. Chỉ dùng ở đường internal — đường công khai trả HOLD_NOT_FOUND. */
    HOLD_NOT_OWNED(403),

    HOLD_EXPIRED(409),

    /** Redis không sẵn sàng. KHÔNG fallback DB-only (ADR-0004) — thà từ chối còn hơn oversell. */
    INVENTORY_UNAVAILABLE(503);

    private final int status;

    InventoryErrorCode(int status) {
        this.status = status;
    }

    /** Vi phạm trần của domain → mã lỗi client hiểu được. */
    public static InventoryErrorCode of(LimitViolation violation) {
        return violation == LimitViolation.CUSTOMER_TOTAL_EXCEEDED ? CUSTOMER_LIMIT_EXCEEDED : HOLD_LIMIT_EXCEEDED;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return status;
    }
}
