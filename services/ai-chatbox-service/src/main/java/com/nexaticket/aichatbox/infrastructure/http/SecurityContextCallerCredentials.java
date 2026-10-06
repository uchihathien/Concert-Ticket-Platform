// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Lấy access token của người đang gọi từ ngữ cảnh bảo mật của request.
 *
 * <p><b>Không lưu, không đưa vào prompt, không ghi log.</b> Token đi thẳng từ đây sang header
 * {@code Authorization} của lời gọi liên service. Nó là bearer token: ai cầm được thì hành động
 * được với tư cách người đó, nên mỗi chỗ nó xuất hiện thêm là một chỗ nữa có thể rò.
 */
@Component
public class SecurityContextCallerCredentials implements CallerCredentialsPort {

    @Override
    public String currentAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        // Không phải trường hợp "chưa đăng nhập" — controller đã đòi xác thực trước đó. Tới được
        // đây nghĩa là use case đang chạy ngoài luồng HTTP (một job, một test), và khi đó "thay mặt
        // ai" là câu hỏi không có đáp án. Ném ra còn hơn im lặng gọi bằng quyền của service.
        throw new IllegalStateException(
                "Không có JWT trong ngữ cảnh bảo mật — agent chỉ chạy trong request đã xác thực");
    }
}
