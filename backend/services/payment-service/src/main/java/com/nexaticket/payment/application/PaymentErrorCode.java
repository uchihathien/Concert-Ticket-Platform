// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của payment. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum PaymentErrorCode implements ErrorCode {
    INTENT_NOT_FOUND(404),

    /**
     * Chưa cấu hình tài khoản ký quỹ nào.
     *
     * <p>500 chứ không phải 400: khách không làm gì sai, đây là lỗi cấu hình của nền tảng. Thà
     * checkout đứng hẳn và ai đó phải xử lý, còn hơn sinh mã QR trỏ vào tài khoản sai.
     */
    NO_ESCROW_ACCOUNT(500),

    /** Chữ ký webhook không hợp lệ — trả 4xx để nhà cung cấp KHÔNG retry. */
    WEBHOOK_UNAUTHORIZED(401),

    /** Payload webhook không đọc được. */
    WEBHOOK_MALFORMED(400),

    BANK_ACCOUNT_INVALID(400);

    private final int status;

    PaymentErrorCode(int status) {
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
