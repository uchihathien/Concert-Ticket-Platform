// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của ordering. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum OrderingErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(404),

    /** Lần giữ chỗ không tồn tại, đã hết hạn, hoặc đã thành đơn khác. */
    HOLD_EXPIRED(409),

    /** Chỗ không còn ở trạng thái đang giữ — có người khác đã lấy. */
    SEAT_UNAVAILABLE(409),

    PROMOTION_INVALID(400),

    /** Đơn không ở trạng thái cho phép thao tác này. */
    ORDER_NOT_OPEN(409),

    /**
     * Một service nội bộ không phản hồi trong hạn. Đã bù trừ xong, khách thử lại được.
     *
     * <p>503 chứ không phải 500: đây là lỗi tạm thời, và client nên hiện "thử lại" chứ không phải
     * "đã có lỗi xảy ra".
     */
    CHECKOUT_UNAVAILABLE(503);

    private final int status;

    OrderingErrorCode(int status) {
        this.status = status;
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
