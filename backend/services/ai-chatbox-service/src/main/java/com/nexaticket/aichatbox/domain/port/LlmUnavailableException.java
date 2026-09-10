// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

/**
 * Không lấy được câu trả lời từ mô hình: quá hạn, quá hạn mức, sự cố phía nhà cung cấp, hoặc mô
 * hình từ chối trả lời.
 *
 * <p>Tách khỏi lỗi tool: tool hỏng thì agent vẫn trả lời được (nói là chưa tra cứu được), còn mô
 * hình hỏng thì không có câu trả lời nào để trả về — hai tình huống đó cho ra hai mã HTTP khác nhau.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
