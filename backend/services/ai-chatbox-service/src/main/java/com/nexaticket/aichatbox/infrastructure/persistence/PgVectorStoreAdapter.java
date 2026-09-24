// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.KnowledgeEntry;
import com.nexaticket.aichatbox.domain.model.RulesEntry;
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

    @Override
    public UUID addChunk(UUID eventId, String title, String content, float[] embedding) {
        UUID id = UUID.randomUUID();
        db.sql(
                        """
                        insert into event_knowledge_embeddings (id, event_id, title, content, embedding)
                        values (:id, :eventId, :title, :content, cast(:embedding as vector))
                        """)
                .param("id", id)
                .param("eventId", eventId)
                .param("title", title)
                .param("content", content)
                .param("embedding", toVectorLiteral(embedding))
                .update();
        return id;
    }

    @Override
    public boolean deleteChunk(UUID id) {
        return db.sql("delete from event_knowledge_embeddings where id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    /**
     * Mới nhất trước: người soạn vừa thêm một đoạn thì thứ họ muốn thấy ngay là đoạn đó.
     *
     * <p>Không chọn cột {@code embedding}: một trang 50 đoạn là 50 × 1024 số thực đi qua dây chỉ để
     * bị bỏ đi ở tầng trên.
     */
    @Override
    public List<KnowledgeEntry> listChunks(UUID eventId, int limit, int offset) {
        String scope = eventId == null ? "" : " where event_id = :eventId";
        var query = db.sql("select id, event_id, title, content, created_at from event_knowledge_embeddings" + scope
                        + " order by created_at desc, id desc limit :limit offset :offset")
                .param("limit", limit)
                .param("offset", offset);
        if (eventId != null) {
            query = query.param("eventId", eventId);
        }
        return query.query((rs, rowNum) -> new KnowledgeEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * Ghi đè trọn bản, và <b>dịch {@code updated_at}</b>.
     *
     * <p>Giá trị mặc định của cột chỉ chạy lúc INSERT; không đặt lại ở nhánh UPDATE thì mọi bản sửa
     * sau lần đầu đều mang mốc thời gian của lần đầu, và "quy định này soạn từ bao giờ" trả lời sai
     * mãi mãi.
     */
    @Override
    public void upsertRules(UUID eventId, String eventTitle, String content, boolean published) {
        db.sql(
                        """
                        insert into event_rules (event_id, event_title, content, published)
                        values (:eventId, :title, :content, :published)
                        on conflict (event_id) do update
                           set event_title = excluded.event_title,
                               content     = excluded.content,
                               published   = excluded.published,
                               updated_at  = now()
                        """)
                .param("eventId", eventId)
                .param("title", eventTitle)
                .param("content", content)
                .param("published", published)
                .update();
    }

    @Override
    public Optional<RulesEntry> findRulesForCurator(UUID eventId) {
        return db.sql(
                        """
                        select event_id, event_title, content, published, updated_at
                        from event_rules
                        where event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new RulesEntry(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_title"),
                        rs.getString("content"),
                        rs.getBoolean("published"),
                        rs.getTimestamp("updated_at").toInstant()))
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
