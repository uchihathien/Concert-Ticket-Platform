// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tham số của Ollama.
 *
 * @param chatModel mô hình trả lời. Mặc định {@code qwen2.5:7b-instruct} vì hai điều kiện phải
 *     thoả cùng lúc: nó hỗ trợ tool calling (phần lớn mô hình nhỏ thì không), và nó viết tiếng
 *     Việt đọc được. Bỏ một trong hai thì agent này không chạy — nó sống bằng tool, và khách là
 *     người Việt.
 * @param embeddingModel mô hình nhúng. Mặc định {@code bge-m3} vì nó sinh vector <b>1024 chiều</b>,
 *     đúng bằng {@code vector(1024)} trong V0100 — cột ấy được khai cho voyage-3.5, và trùng số
 *     chiều là điều kiện để đổi nhà cung cấp mà không phải sửa schema. Đổi sang mô hình 768 chiều
 *     (ví dụ nomic-embed-text) thì phải đổi cột VÀ nhúng lại toàn bộ kho tri thức; xem ghi chú ở
 *     {@code EmbeddingPort}.
 * @param timeout rộng hơn hẳn hạn của API trả phí. Mô hình local chạy trên CPU thì một lượt 30–60
 *     giây là bình thường, và cắt ở 60s như cấu hình Anthropic sẽ biến máy chậm thành "trợ lý luôn
 *     hỏng".
 */
@ConfigurationProperties(prefix = "nexaticket.aichatbox.ollama")
public record OllamaProperties(
        String baseUrl, String chatModel, String embeddingModel, int dimensions, Duration timeout) {

    public OllamaProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (chatModel == null || chatModel.isBlank()) {
            chatModel = "qwen2.5:7b-instruct";
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "bge-m3";
        }
        if (dimensions <= 0) {
            dimensions = 1024;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(120);
        }
    }
}
