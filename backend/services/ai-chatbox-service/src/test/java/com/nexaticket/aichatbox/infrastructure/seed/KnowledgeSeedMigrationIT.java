// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * V0109 phải chạy được trên DB ĐANG có bản trùng — đó là cả lý do nó dọn trước khi tạo index. Chạy
 * lại nguyên văn tệp migration trên một bảng đã bỏ index và đã gieo bản trùng.
 */
class KnowledgeSeedMigrationIT extends AiChatboxTestBase {

    @Autowired
    JdbcClient db;

    @Autowired
    DataSource dataSource;

    private void insert(String title, String content, String createdAt) {
        StringBuilder vector = new StringBuilder("[1");
        for (int i = 1; i < 1024; i++) {
            vector.append(",0");
        }
        vector.append(']');
        db.sql(
                        """
                        insert into event_knowledge_embeddings (id, event_id, title, content, embedding, created_at)
                        values (:id, null, :title, :content, cast(:v as vector), cast(:at as timestamptz))
                        """)
                .param("id", UUID.randomUUID())
                .param("title", title)
                .param("content", content)
                .param("v", vector.toString())
                .param("at", createdAt)
                .update();
    }

    @Test
    void xoa_ban_trung_hoan_toan_doi_ten_ban_khac_noi_dung_roi_tao_index() {
        String dup = "Trùng hoàn toàn " + UUID.randomUUID();
        String clash = "Cùng tiêu đề " + UUID.randomUUID();
        try {
            db.sql("drop index if exists uq_knowledge_global_title").update();
            insert(dup, "nội dung A", "2026-01-01T00:00:00Z");
            insert(dup, "nội dung A", "2026-01-02T00:00:00Z");
            insert(clash, "bản gốc", "2026-01-01T00:00:00Z");
            insert(clash, "bản do người khác soạn", "2026-01-03T00:00:00Z");

            new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/aichatbox/V0109__knowledge_seed_unique.sql"))
                    .execute(dataSource);

            List<String> titles = db.sql(
                            "select title from event_knowledge_embeddings where event_id is null order by created_at, title")
                    .query(String.class)
                    .list();
            assertThat(titles).containsExactlyInAnyOrder(dup, clash, clash + " (2)");
            // Bản sớm nhất được giữ, nội dung bị đổi tên còn nguyên.
            assertThat(db.sql("select content from event_knowledge_embeddings where title = :t")
                            .param("t", clash + " (2)")
                            .query(String.class)
                            .single())
                    .isEqualTo("bản do người khác soạn");
            assertThat(db.sql("select count(*) from pg_indexes where indexname = 'uq_knowledge_global_title'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            db.sql("create unique index if not exists uq_knowledge_global_title on event_knowledge_embeddings (title) "
                            + "where event_id is null")
                    .update();
        }
    }
}
