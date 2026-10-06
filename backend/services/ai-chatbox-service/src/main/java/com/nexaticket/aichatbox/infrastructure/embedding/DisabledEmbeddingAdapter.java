// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.embedding;

import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Nhúng đã TẮT ({@code EMBEDDING_PROVIDER=none}).
 *
 * <h2>Vì sao là một bean ném lỗi chứ không phải "không có bean"</h2>
 *
 * <p>{@code CustomerSupportAgentUseCase} và {@code KnowledgeBaseUseCase} đều nhận {@link EmbeddingPort}
 * qua hàm dựng. Bỏ hẳn bean nghĩa là sửa cả hai chỗ đó thành {@code Optional} rồi rải thêm nhánh rẽ —
 * nhiều mã hơn cho một cấu hình vốn là lựa chọn cuối.
 *
 * <p>Ném lỗi thì đi đúng vào đường đã có sẵn: bước tìm ngữ cảnh của use case bắt {@code RuntimeException}
 * và trả lời không kèm tri thức nền (cố ý — mất RAG không phải hỏng), còn {@code KnowledgeSeeder} bắt và
 * ghi một dòng WARN rồi đi tiếp. Nên trợ lý vẫn chạy, vẫn tra được đơn hàng, chỉ không có kho tri thức.
 *
 * <h2>{@code dimensions()} vẫn phải trả số thật</h2>
 *
 * <p>Nó được dùng để kiểm cột {@code vector(n)} của V0100, không phải để nhúng. Trả 0 hay ném lỗi ở đây
 * sẽ làm một phép kiểm lược đồ thất bại vì một lý do không liên quan gì tới lược đồ.
 */
@Component
@ConditionalOnProperty(name = EmbeddingProvider.PROPERTY, havingValue = "none")
public class DisabledEmbeddingAdapter implements EmbeddingPort {
    private static final Logger log = LoggerFactory.getLogger(DisabledEmbeddingAdapter.class);

    private final int dimensions;

    public DisabledEmbeddingAdapter(@Value("${nexaticket.aichatbox.embedding.dimensions:1024}") int dimensions) {
        this.dimensions = dimensions;
        log.warn("EMBEDDING_PROVIDER=none — trợ lý chạy KHÔNG có tri thức nền. "
                + "Câu hỏi chính sách chỉ được trả lời từ prompt hệ thống. "
                + "Đặt EMBEDDING_PROVIDER=local (Ollama, miễn phí) hoặc =anthropic (Voyage) để bật lại RAG.");
    }

    @Override
    public float[] embedQuery(String text) {
        throw new IllegalStateException("nhúng đã tắt (EMBEDDING_PROVIDER=none)");
    }

    @Override
    public float[] embedDocument(String text) {
        throw new IllegalStateException("nhúng đã tắt (EMBEDDING_PROVIDER=none)");
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
