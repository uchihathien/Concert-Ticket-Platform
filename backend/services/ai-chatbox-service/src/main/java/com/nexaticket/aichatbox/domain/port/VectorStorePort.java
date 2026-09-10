// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho tri thức của context này, nằm trong {@code ai_chatbox_db}. */
public interface VectorStorePort {

    /**
     * Tìm các đoạn gần câu hỏi nhất.
     *
     * <p>Luôn trả về đủ {@code topK} nếu kho có đủ dữ liệu, <b>kể cả khi chẳng đoạn nào liên
     * quan</b> — lọc theo ngưỡng khoảng cách là việc của người gọi.
     */
    List<KnowledgeChunk> searchSimilar(float[] queryEmbedding, int topK);

    /** Tra quy định của một sự kiện. Tra chính xác theo id, không phải tìm ngữ nghĩa. */
    Optional<EventRules> findRules(UUID eventId);
}
