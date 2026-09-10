// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

/**
 * Access token của người đang gọi, để agent gọi service khác <b>thay mặt họ</b>.
 *
 * <p>Là một port thay vì một tham số truyền tay qua bốn tầng: token là chi tiết của lớp vận
 * chuyển, và {@code executeAgentProcess(sessionId, userId, query)} không nên mang theo một bí mật
 * mà bản thân nó không dùng. Adapter đọc token từ ngữ cảnh bảo mật của request hiện tại.
 */
public interface CallerCredentialsPort {

    /**
     * @return token thô, không có tiền tố "Bearer"
     * @throws IllegalStateException khi không có request đã xác thực — nghĩa là ai đó gọi use case
     *     ngoài luồng HTTP, và lúc đó gọi service khác thay mặt ai là một câu hỏi không có đáp án
     */
    String currentAccessToken();
}
