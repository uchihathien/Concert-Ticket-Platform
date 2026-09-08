// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của payout. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum PayoutErrorCode implements ErrorCode {
    BATCH_NOT_FOUND(404),

    /** Chưa cấu hình đích chi trả cho tổ chức này. */
    NO_PAYOUT_ACCOUNT(400),

    /** Một hoặc nhiều cổng chặn không qua; danh sách nằm trong meta. */
    PAYOUT_BLOCKED(422),

    /** Người tạo lô tự duyệt lô của mình. */
    SELF_APPROVAL_FORBIDDEN(403),

    BATCH_NOT_IN_EXPECTED_STATE(409);

    private final int status;

    PayoutErrorCode(int status) {
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
