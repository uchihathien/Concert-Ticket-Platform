// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeViews;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Nạp tri thức nền.
 *
 * <p>Gọi thẳng {@code seed()} thay vì dựng một context có seeder bật: nó chạy trên một luồng ảo ở
 * lúc khởi động, nên khẳng định trên kết quả của nó sẽ là một cuộc đua. Thứ cần kiểm là <b>logic</b>
 * — nạp phần còn thiếu, chịu được hai replica — chứ không phải việc Spring có gọi ApplicationRunner.
 */
class KnowledgeSeederIT extends AiChatboxTestBase {

    private static final int STARTER = StarterKnowledge.all().size();

    @Autowired
    KnowledgeBaseUseCase knowledge;

    @Autowired
    VectorStorePort store;

    @Autowired
    AgentProperties agent;

    @Autowired
    JdbcClient db;

    @Autowired
    DataSource dataSource;

    /** Bộ nhúng giả đếm lời gọi, có thể chậm và có thể hỏng từ lần gọi thứ N. */
    static class ScriptedEmbeddings extends FakeAiProviders.FakeEmbeddings {
        final AtomicInteger calls = new AtomicInteger();
        final int failFrom;
        final long delayMillis;

        ScriptedEmbeddings(int failFrom, long delayMillis) {
            this.failFrom = failFrom;
            this.delayMillis = delayMillis;
        }

        static ScriptedEmbeddings healthy() {
            return new ScriptedEmbeddings(Integer.MAX_VALUE, 0);
        }

        @Override
        public float[] embedDocument(String text) {
            int n = calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (n >= failFrom) {
                throw new IllegalStateException("bộ nhúng giả hỏng ở lần gọi " + n);
            }
            return super.embedDocument(text);
        }
    }

    private KnowledgeSeeder seeder(ScriptedEmbeddings embeddings) {
        return new KnowledgeSeeder(new KnowledgeBaseUseCase(store, embeddings, agent), db, dataSource);
    }

    private KnowledgeSeeder seeder() {
        return new KnowledgeSeeder(knowledge, db, dataSource);
    }

    private List<KnowledgeViews.ChunkRow> everything() {
        return knowledge.listChunks(null, 100, 0);
    }

    private int globalCount() {
        return db.sql("select count(*) from event_knowledge_embeddings where event_id is null")
                .query(Integer.class)
                .single();
    }

    private int distinctGlobalTitles() {
        return db.sql("select count(distinct title) from event_knowledge_embeddings where event_id is null")
                .query(Integer.class)
                .single();
    }

    @Test
    void kho_trong_thi_nap_day_du_va_tim_lai_duoc() {
        seeder().seed();

        assertThat(everything()).hasSize(STARTER);

        // Không chỉ kiểm "đã ghi vào bảng": phải lấy ra được bằng ĐÚNG đường agent đi, và đúng đoạn
        // phải đứng đầu.
        //
        // Cố ý KHÔNG khẳng định cờ `used`. Bộ nhúng ở test là túi từ (xem FakeAiProviders), nên một
        // câu hỏi bốn từ so với một đoạn sáu mươi từ luôn cho khoảng cách lớn — ở đây là 0,68 trong
        // khi ngưỡng là 0,55 — dù thứ hạng hoàn toàn đúng. Mô hình thật (bge-m3, voyage-3.5) nhúng
        // theo ngữ nghĩa nên không có hiệu ứng đó. Khẳng định `used` ở đây là khẳng định về hàng
        // giả, không phải về hệ thống; câu hỏi "ngưỡng đặt bao nhiêu là đúng" thuộc về người vận
        // hành, và endpoint `preview` tồn tại chính để họ trả lời nó trên dữ liệu thật.
        assertThat(knowledge.preview(null, "giữ chỗ được bao lâu")).first().satisfies(row -> assertThat(row.title())
                .contains("giữ chỗ"));
    }

    /** R-10: lần đầu hỏng giữa chừng thì lần sau nạp TIẾP phần còn thiếu, không bỏ dở vĩnh viễn. */
    @Test
    void lan_dau_hong_o_doan_thu_4_thi_lan_sau_nap_tiep_phan_con_lai() {
        seeder(new ScriptedEmbeddings(4, 0)).seed();
        assertThat(globalCount()).isEqualTo(3);

        ScriptedEmbeddings healthy = ScriptedEmbeddings.healthy();
        seeder(healthy).seed();

        assertThat(globalCount()).isEqualTo(STARTER);
        assertThat(distinctGlobalTitles()).isEqualTo(STARTER);
        assertThat(healthy.calls).hasValue(STARTER - 3);
    }

    /** R-10, điều kiện cũ: đoạn của sự kiện hay đoạn chung do người soạn không làm kho "không trống". */
    @Test
    void kho_da_co_doan_su_kien_va_doan_tu_soan_van_nap_du_tri_thuc_nen() {
        knowledge.addChunk(UUID.randomUUID(), "Quy định riêng", "Không mang chai thuỷ tinh.");
        knowledge.addChunk(null, "Đoạn do đội vận hành soạn " + UUID.randomUUID(), "Nội dung riêng.");

        seeder().seed();

        assertThat(globalCount()).isEqualTo(STARTER + 1);
        assertThat(db.sql("select count(*) from event_knowledge_embeddings where event_id is not null")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    /** AC #4: kho đủ thì không gọi mô hình nhúng lần nào. */
    @Test
    void kho_da_du_thi_khong_nhung_lan_nao() {
        seeder().seed();
        ScriptedEmbeddings counting = ScriptedEmbeddings.healthy();

        seeder(counting).seed();

        assertThat(counting.calls).hasValue(0);
        assertThat(globalCount()).isEqualTo(STARTER);
    }

    @Test
    void chay_hai_lan_khong_nhan_doi_kho() {
        seeder().seed();
        seeder().seed();

        assertThat(everything()).hasSize(STARTER);
    }

    /** R-11: hai replica khởi động cùng lúc trên DB trống. */
    @Test
    void hai_seeder_song_song_cho_dung_mot_bo_tri_thuc_nen() throws Exception {
        runTogether(
                () -> seeder(new ScriptedEmbeddings(Integer.MAX_VALUE, 200)).seed(),
                () -> seeder(new ScriptedEmbeddings(Integer.MAX_VALUE, 200)).seed());

        assertThat(globalCount()).isEqualTo(STARTER);
        assertThat(distinctGlobalTitles()).isEqualTo(STARTER);
    }

    /** Bảo hiểm khi khoá lọt: nạp thẳng (không khoá) hai lần song song — unique index vẫn chặn trùng. */
    @Test
    void lot_khoa_thi_unique_index_van_chan_trung() throws Exception {
        runTogether(
                () -> seeder(new ScriptedEmbeddings(Integer.MAX_VALUE, 50)).load(),
                () -> seeder(new ScriptedEmbeddings(Integer.MAX_VALUE, 50)).load());

        assertThat(globalCount()).isEqualTo(STARTER);
    }

    /** Khoá đang bị giữ (replica khác đang nạp) thì trả về ngay, không nhúng gì. */
    @Test
    void khoa_dang_bi_giu_thi_bo_qua() throws Exception {
        ScriptedEmbeddings counting = ScriptedEmbeddings.healthy();
        try (Connection other = dataSource.getConnection()) {
            advisory(other, "pg_advisory_lock");
            try {
                seeder(counting).seed();
            } finally {
                advisory(other, "pg_advisory_unlock");
            }
        }

        assertThat(counting.calls).hasValue(0);
        assertThat(globalCount()).isZero();
    }

    /** Kết nối giữ khoá được trả về pool cả khi nhúng ném lỗi — và khoá được nhả theo. */
    @Test
    void ket_noi_giu_khoa_duoc_tra_ca_khi_nhung_hong() throws Exception {
        var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        int before = pool.getActiveConnections();

        seeder(new ScriptedEmbeddings(1, 0)).seed();

        assertThat(pool.getActiveConnections()).isEqualTo(before);
        // Khoá đã nhả: một kết nối khác lấy lại được ngay.
        try (Connection other = dataSource.getConnection()) {
            assertThat(advisory(other, "pg_try_advisory_lock")).isTrue();
            advisory(other, "pg_advisory_unlock");
        }
    }

    /**
     * Hai con số trong tri thức nền phải khớp mã nguồn, vì trợ lý sẽ đọc chúng ra như sự thật.
     *
     * <p>Test này không kiểm hành vi mà kiểm <b>tính đúng của nội dung</b> — nó tồn tại để một lần
     * đổi {@code hold_ttl_seconds} hay {@code payment-window} không lặng lẽ biến trợ lý thành nguồn
     * thông tin sai. Đổi hằng số ở kia thì test này đỏ, và đó đúng là lúc cần sửa câu chữ ở đây.
     */
    @Test
    void con_so_trong_tri_thuc_nen_khop_voi_cau_hinh_that() {
        String all = StarterKnowledge.all().stream()
                .map(chunk -> chunk.title() + " " + chunk.content())
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(all).contains("10 phút"); // inventory V0100: hold_ttl_seconds DEFAULT 600
        assertThat(all).contains("15 phút"); // ordering: payment-window: 15m
        assertThat(all).contains("1900 1234"); // khớp SupportAgentPrompts.SYSTEM
    }

    /** Tri thức nền là của NỀN TẢNG, không thuộc sự kiện nào — nếu không nó biến mất khỏi mọi câu hỏi chung. */
    @Test
    void tri_thuc_nen_khong_gan_voi_su_kien_nao() {
        seeder().seed();

        assertThat(everything()).allSatisfy(row -> assertThat(row.eventId()).isNull());
    }

    private static void runTogether(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> {
                start.await();
                first.run();
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                second.run();
                return null;
            });
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean advisory(Connection connection, String function) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select " + function + "(?)::text")) {
            statement.setLong(1, KnowledgeSeeder.ADVISORY_LOCK_KEY);
            try (var rs = statement.executeQuery()) {
                rs.next();
                // pg_advisory_lock trả void (chuỗi rỗng); hai hàm còn lại trả boolean.
                return "true".equals(rs.getString(1));
            }
        }
    }
}
