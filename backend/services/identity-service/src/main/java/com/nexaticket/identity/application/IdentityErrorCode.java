// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của identity. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum IdentityErrorCode implements ErrorCode {
    ORGANIZATION_NOT_FOUND(404),
    USER_NOT_FOUND(404),
    SLUG_ALREADY_TAKEN(409),
    NOT_A_MEMBER(404),
    ALREADY_A_MEMBER(409),
    LAST_OWNER(409),
    INVITATION_INVALID(400),
    INVITATION_EXPIRED(410),
    INVITATION_ALREADY_USED(409),
    EMAIL_MISMATCH(403),
    ORGANIZATION_SUSPENDED(409),
    /** Tài khoản đã bị vô hiệu hoá — khôi phục trước khi làm gì khác với nó. */
    USER_DISABLED(409),
    /**
     * Không nhờ được Keycloak gửi thư đặt lại mật khẩu.
     *
     * <p>503 chứ không 500: đây là một phụ thuộc ngoài không sẵn sàng (chưa cấu hình Admin API,
     * hoặc Keycloak không trả lời), và người gọi thử lại được. 500 nói "hệ thống hỏng" và đẩy người
     * ta đi đọc log của identity, trong khi nguyên nhân nằm ở chỗ khác.
     */
    PASSWORD_RESET_UNAVAILABLE(503),
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
