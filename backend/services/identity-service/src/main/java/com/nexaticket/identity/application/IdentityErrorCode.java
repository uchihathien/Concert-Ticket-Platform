// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của identity. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum IdentityErrorCode implements ErrorCode {
    ORGANIZATION_NOT_FOUND(404),
    SLUG_ALREADY_TAKEN(409),
    NOT_A_MEMBER(404),
    ALREADY_A_MEMBER(409),
    LAST_OWNER(409),
    INVITATION_INVALID(400),
    INVITATION_EXPIRED(410),
    INVITATION_ALREADY_USED(409),
    EMAIL_MISMATCH(403),
    ORGANIZATION_SUSPENDED(409),
    SCANNER_CODE_INVALID(400),
    SCANNER_CODE_EXHAUSTED(409);

    private final int status;

    IdentityErrorCode(int status) {
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
