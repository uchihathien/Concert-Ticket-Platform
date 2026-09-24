// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.knowledge;

import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.KnowledgeEntry;
import com.nexaticket.aichatbox.domain.model.RulesEntry;
import java.time.Instant;
import java.util.UUID;

/** DTO của màn hình soạn kho tri thức. */
public final class KnowledgeViews {

    private KnowledgeViews() {}

    /** @param eventId {@code null} nghĩa là tri thức chung của nền tảng */
    public record ChunkRow(UUID id, UUID eventId, String title, String content, Instant createdAt) {

        public static ChunkRow of(KnowledgeEntry entry) {
            return new ChunkRow(entry.id(), entry.eventId(), entry.title(), entry.content(), entry.createdAt());
        }
    }

    /**
     * Một đoạn lấy ra được cho câu hỏi thử, kèm khoảng cách.
     *
     * @param distance khoảng cách cosine — số này là lý do endpoint thử tồn tại. Kho tri thức
     *     "trông đầy" mà mọi đoạn đều nằm trên ngưỡng thì trợ lý không dùng đoạn nào, và không có
     *     cách nào khác để biết điều đó trước khi khách hỏi.
     * @param used đoạn này có vượt được ngưỡng {@code max-retrieval-distance} không, tức trợ lý
     *     có thật sự đọc nó không
     */
    public record RetrievedRow(UUID id, UUID eventId, String title, String content, double distance, boolean used) {

        public static RetrievedRow of(KnowledgeChunk chunk, double threshold) {
            return new RetrievedRow(
                    chunk.id(),
                    chunk.eventId(),
                    chunk.title(),
                    chunk.content(),
                    chunk.distance(),
                    chunk.distance() <= threshold);
        }
    }

    /** @param published {@code false} là bản nháp — khách không thấy, tool không đọc */
    public record RulesRow(UUID eventId, String eventTitle, String content, boolean published, Instant updatedAt) {

        public static RulesRow of(RulesEntry entry) {
            return new RulesRow(
                    entry.eventId(), entry.eventTitle(), entry.content(), entry.published(), entry.updatedAt());
        }
    }
}
