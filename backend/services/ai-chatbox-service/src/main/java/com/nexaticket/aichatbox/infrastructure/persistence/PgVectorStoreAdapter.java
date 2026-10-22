// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.KnowledgeEntry;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.RulesEntry;
import com.nexaticket.aichatbox.domain.port.KnowledgeTitleTakenException;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Kho tri thức trên PostgreSQL + pgvector. */
@Repository
public class PgVectorStoreAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreAdapter.class);

    /** Mặc định của pgvector — không bao giờ đặt thấp hơn. */
    private static final int MIN_EF_SEARCH = 40;

    /** Trần của pgvector cho {@code hnsw.ef_search}. */
    private static final int MAX_EF_SEARCH = 1000;

    private final JdbcClient db;
    private final TransactionTemplate readOnly;
    private final int efSearchFactor;
    private final boolean iterativeScan;

    public PgVectorStoreAdapter(
            JdbcClient db,
            PlatformTransactionManager transactions,
            @Value("${nexaticket.aichatbox.knowledge.ef-search-factor:10}") int efSearchFactor) {
        this.db = db;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.efSearchFactor = efSearchFactor;
        String version = vectorVersion();
        this.iterativeScan = atLeast(version, 0, 8);
        log.info(
                "pgvector {} — iterative scan {}, ef_search = max(topK × {}, {})",
                version,
                iterativeScan ? "bật" : "không hỗ trợ",
                efSearchFactor,
                MIN_EF_SEARCH);
    }

    private String vectorVersion() {
        try {
            return db.sql("select extversion from pg_extension where extname = 'vector'")
                    .query(String.class)
                    .optional()
                    .orElse("?");
        } catch (DataAccessException e) {
            log.warn("Không đọc được phiên bản pgvector: {}", e.getMessage());
            return "?";
        }
    }

    /** "0.8.6" ≥ 0.8? Chuỗi lạ thì coi là không — tắt một tối ưu, không hỏng truy vấn. */
    static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("\\.");
        try {
            int ma = Integer.parseInt(parts[0]);
            int mi = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return ma > major || (ma == major && mi >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code max(topK × hệ số, 40)}, không quá trần của pgvector. */
    static int efSearchFor(int topK, int efSearchFactor) {
        long wanted = Math.max((long) topK * efSearchFactor, MIN_EF_SEARCH);
        return (int) Math.min(wanted, MAX_EF_SEARCH);
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
    public List<KnowledgeChunk> searchSimilar(float[] queryEmbedding, UUID eventId, int topK) {
        // `event_id is null` = tri thức chung, luôn nằm trong phạm vi. Khi biết sự kiện thì cộng
        // thêm đoạn của chính sự kiện ấy — không bao giờ của sự kiện khác.
        //
        // Index HNSW lấy `ef_search` ứng viên gần nhất (mặc định 40) RỒI mới áp WHERE (R-29): kho có
        // nhiều đoạn của sự kiện khác gần câu hỏi hơn thì đoạn của chính sự kiện này bị lọc sạch
        // khỏi 40 ứng viên ấy và trả về thiếu. Nới `ef_search`, và với pgvector ≥ 0.8 bật iterative
        // scan để index quét tiếp tới khi đủ k dòng qua bộ lọc.
        //
        // `set_config(..., true)` chỉ sống trong transaction hiện tại — nên phải có transaction, và
        // nó chỉ chứa hai ba câu SQL, không có lời gọi mạng nào.
        return readOnly.execute(status -> {
            db.sql("select set_config('hnsw.ef_search', :n, true)")
                    .param("n", String.valueOf(efSearchFor(topK, efSearchFactor)))
                    .query(String.class)
                    .single();
            if (iterativeScan) {
                db.sql("select set_config('hnsw.iterative_scan', 'relaxed_order', true)")
                        .query(String.class)
                        .single();
            }
            // relaxed_order có thể trả lệch thứ tự đôi chút — xếp lại trên tập đã lấy.
            return db.sql(
                            """
                            with candidates as materialized (
                                select id, event_id, title, content, embedding <=> cast(:q as vector) as distance
                                from event_knowledge_embeddings
                                where event_id is null or event_id = :eventId
                                order by embedding <=> cast(:q as vector)
                                limit :k
                            )
                            select * from candidates order by distance
                            """)
                    .param("q", toVectorLiteral(queryEmbedding))
                    .param("eventId", eventId)
                    .param("k", topK)
                    .query((rs, rowNum) -> new KnowledgeChunk(
                            rs.getObject("id", UUID.class),
                            rs.getObject("event_id", UUID.class),
                            rs.getString("title"),
                            rs.getString("content"),
                            rs.getDouble("distance")))
                    .list();
        });
    }

    @Override
    public Optional<EventRules> findRules(UUID eventId) {
        return db.sql(
                        """
                        select event_id, event_title, content, refund_allowed, refund_window_hours
                        from event_rules
                        where event_id = :eventId and published
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new EventRules(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_title"),
                        rs.getString("content"),
                        new RefundPolicy(rs.getBoolean("refund_allowed"), rs.getInt("refund_window_hours"))))
                .optional();
    }

    @Override
    public UUID addChunk(UUID eventId, String title, String content, float[] embedding) {
        UUID id = UUID.randomUUID();
        try {
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
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("uq_knowledge_global_title")) {
                throw new KnowledgeTitleTakenException(title);
            }
            throw e;
        }
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
    public void upsertRules(
            UUID eventId, String eventTitle, String content, RefundPolicy refundPolicy, boolean published) {
        db.sql(
                        """
                        insert into event_rules
                            (event_id, event_title, content, refund_allowed, refund_window_hours, published)
                        values (:eventId, :title, :content, :refundAllowed, :refundWindowHours, :published)
                        on conflict (event_id) do update
                           set event_title         = excluded.event_title,
                               content             = excluded.content,
                               refund_allowed      = excluded.refund_allowed,
                               refund_window_hours = excluded.refund_window_hours,
                               published           = excluded.published,
                               updated_at          = now()
                        """)
                .param("eventId", eventId)
                .param("title", eventTitle)
                .param("content", content)
                .param("refundAllowed", refundPolicy.allowed())
                .param("refundWindowHours", refundPolicy.windowHours())
                .param("published", published)
                .update();
    }

    @Override
    public Optional<RulesEntry> findRulesForCurator(UUID eventId) {
        return db.sql(
                        """
                        select event_id, event_title, content, refund_allowed, refund_window_hours,
                               published, updated_at
                        from event_rules
                        where event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new RulesEntry(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_title"),
                        rs.getString("content"),
                        new RefundPolicy(rs.getBoolean("refund_allowed"), rs.getInt("refund_window_hours")),
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
