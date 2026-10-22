// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Cấu hình nhà cung cấp mô hình.
 *
 * @param apiKey khoá API. Không có mặc định — thiếu thì service không khởi động được, và đó là
 *     điều đúng: một agent không gọi được mô hình thì mọi request đều 503, còn tệ hơn là không bật.
 * @param maxTokens trần token cho MỘT lượt trả lời, không phải mục tiêu. Đặt thấp thì câu trả lời
 *     bị cắt giữa chừng và phải hỏi lại — tốn hơn là để rộng.
 * @param effort độ sâu suy luận: low, medium, high, xhigh, max. Chat hỗ trợ là loại việc mà mức
 *     thấp thường đủ, và mỗi nấc cao hơn là thêm token suy luận phải trả tiền cho mỗi câu hỏi.
 * @param timeout hạn cho một lời gọi. Mặc định của SDK là 10 phút — quá dài cho một người đang
 *     nhìn màn hình chat, và đủ để một sự cố phía nhà cung cấp giữ hết luồng của service.
 * @param maxRetries số lần SDK tự thử lại. Mặc định của SDK là 2 — mỗi lời gọi thành tới 3 × {@code
 *     timeout}, và ngân sách thời gian của một lượt (xem {@code spring.mvc.async} trong
 *     application.yml) phải tính cả nó. Khai tường minh để con số đó là của ta, không phải của SDK.
 */
@ConfigurationProperties(prefix = "nexaticket.aichatbox.anthropic")
public record AnthropicProperties(String apiKey, Long maxTokens, String effort, Duration timeout, Integer maxRetries) {

    /** Bốn tham số như trước khi có {@code maxRetries} — giữ cho chỗ dựng tay (test) không phải đổi. */
    public AnthropicProperties(String apiKey, Long maxTokens, String effort, Duration timeout) {
        this(apiKey, maxTokens, effort, timeout, null);
    }

    // Hai hàm dựng ⇒ phải chỉ cho binder hàm nào, nếu không cấu hình không được nạp.
    @ConstructorBinding
    public AnthropicProperties {
        if (maxRetries == null || maxRetries < 0) {
            maxRetries = 1;
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 8000L;
        }
        if (effort == null || effort.isBlank()) {
            effort = "medium";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
    }
}
