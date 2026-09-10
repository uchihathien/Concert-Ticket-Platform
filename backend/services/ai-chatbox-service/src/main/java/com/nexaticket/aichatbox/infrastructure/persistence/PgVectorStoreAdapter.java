// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Kho tri thức trên PostgreSQL + pgvector. */
@Repository
public class PgVectorStoreAdapter implements VectorStorePort {

    private final JdbcClient db;

    public PgVectorStoreAdapter(JdbcClient db) {
        this.db = db;
    }

    /**
     * Tìm láng giềng gần nhất theo khoảng cách cosine.
     *
     * <p><b>{@code <=>} là cosine, {@code <->} là Euclid.</b> Hai toán tử này đều chạy, đều trả về
     * số, và đều sắp xếp được — nên chọn nhầm không gây lỗi, chỉ cho ra kết quả kém hơn. Vector
     * nhúng văn bản so bằng cosine.
     *
     * <p>Vector truyền dưới dạng chuỗi {@code '[0.1,0.2,...]'} rồi ép kiểu: driver JDBC không biết
     * kiểu {@code vector}, và đây là cách nối hai bên mà không cần thêm thư viện.
     */
    @Override
    public List<KnowledgeChunk> searchSimilar(float[] queryEmbedding, int topK) {
        return db.sql(
                        """
                        select id, event_id, title, content, embedding <=> cast(:q as vector) as distance
                        from event_knowledge_embeddings
                        order by embedding <=> cast(:q as vector)
                        limit :k
                        """)
                .param("q", toVectorLiteral(queryEmbedding))
                .param("k", topK)
                .query((rs, rowNum) -> new KnowledgeChunk(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getDouble("distance")))
                .list();
    }

    @Override
    public Optional<EventRules> findRules(UUID eventId) {
        return db.sql(
                        """
                        select event_id, event_title, content
                        from event_rules
                        where event_id = :eventId and published
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new EventRules(
                        rs.getObject("event_id", UUID.class), rs.getString("event_title"), rs.getString("content")))
                .optional();
    }

    private static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
