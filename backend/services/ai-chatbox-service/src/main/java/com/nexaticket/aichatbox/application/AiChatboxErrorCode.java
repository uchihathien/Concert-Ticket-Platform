// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của ai-chatbox. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum AiChatboxErrorCode implements ErrorCode {

    /** Phiên chat không tồn tại hoặc thuộc về người khác. Cố ý không phân biệt hai trường hợp. */
    CHAT_SESSION_NOT_FOUND(404),

    /**
     * Không lấy được câu trả lời từ mô hình.
     *
     * <p>503 chứ không phải 500: nhà cung cấp quá tải là chuyện thường xuyên và tạm thời, client
     * nên hiện "thử lại" chứ không phải "đã có lỗi xảy ra".
     */
    ASSISTANT_UNAVAILABLE(503);

    private final int status;

    AiChatboxErrorCode(int status) {
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
