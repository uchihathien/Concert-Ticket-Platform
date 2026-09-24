// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import com.nexaticket.aichatbox.infrastructure.http.PooledHttpFactory;
import com.nexaticket.aichatbox.infrastructure.llm.LlmProvider;
import com.nexaticket.aichatbox.infrastructure.llm.OllamaProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Nhúng văn bản bằng mô hình chạy tại chỗ.
 *
 * <h2>Số chiều là hợp đồng với database</h2>
 *
 * <p>{@code bge-m3} sinh vector 1024 chiều, đúng bằng {@code vector(1024)} mà V0100 khai cho
 * voyage-3.5. Sự trùng khớp đó <b>là lý do</b> chọn mô hình này: nó cho phép đổi nhà cung cấp mà
 * không phải sửa schema.
 *
 * <p>Nhưng trùng số chiều KHÔNG có nghĩa là hai bên so sánh được. Vector của hai mô hình khác nhau
 * nằm trong hai không gian khác nhau; trộn chúng trong một bảng thì truy vấn vẫn chạy, vẫn trả về
 * top-k, và kết quả vô nghĩa — một kiểu hỏng không có thông báo lỗi nào. Đổi nhà cung cấp là phải
 * <b>nhúng lại toàn bộ kho tri thức</b>, không có đường tắt.
 *
 * <h2>Không phân biệt câu hỏi với tài liệu</h2>
 *
 * <p>Voyage có hai chế độ ({@code input_type}) và chất lượng tìm kiếm phụ thuộc vào việc khai
 * đúng. {@code bge-m3} không có tham số đó — nó được huấn luyện đối xứng. Nên hai phương thức ở
 * đây gọi cùng một chỗ, và điều đó đúng chứ không phải chưa làm xong.
 */
@Component
@ConditionalOnProperty(name = LlmProvider.PROPERTY, havingValue = "local", matchIfMissing = true)
public class OllamaEmbeddingAdapter implements EmbeddingPort {

    private final RestClient client;
    private final OllamaProperties properties;

    public OllamaEmbeddingAdapter(RestClient.Builder builder, OllamaProperties properties) {
        // Nhúng một câu hỏi rẻ hơn hẳn một lượt chat, nhưng hạn đọc vẫn phải rộng hơn API trả phí:
        // lần gọi đầu tiên sau khi khởi động còn phải nạp mô hình vào bộ nhớ.
        this.client = builder.baseUrl(properties.baseUrl())
                .requestFactory(PooledHttpFactory.create(Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .build();
        this.properties = properties;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text);
    }

    @Override
    public float[] embedDocument(String text) {
        return embed(text);
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }

    private float[] embed(String text) {
        JsonNode response = client.post()
                .uri("/api/embed")
                .body(Map.of("model", properties.embeddingModel(), "input", text))
                .retrieve()
                .body(JsonNode.class);

        JsonNode vector = response == null ? null : response.path("embeddings").path(0);
        if (vector == null || !vector.isArray() || vector.isEmpty()) {
            throw new IllegalStateException("Ollama không trả vector cho mô hình " + properties.embeddingModel());
        }
        if (vector.size() != properties.dimensions()) {
            // Ném thay vì ghi log rồi chạy tiếp: một vector sai số chiều hoặc bị Postgres từ chối
            // (may), hoặc lọt vào bảng và làm hỏng mọi kết quả tìm kiếm sau đó mà không ai biết.
            throw new IllegalStateException("Mô hình %s trả %d chiều, cấu hình khai %d"
                    .formatted(properties.embeddingModel(), vector.size(), properties.dimensions()));
        }

        float[] embedding = new float[vector.size()];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = (float) vector.get(i).asDouble();
        }
        return embedding;
    }
}
