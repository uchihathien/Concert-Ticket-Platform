// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.seed;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.platform.web.error.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Nạp những đoạn tri thức nền còn thiếu.
 *
 * <h2>Không phải "demo data", và tên khoá cấu hình nói ra điều đó</h2>
 *
 * <p>{@code DemoCatalogSeeder} dựng tổ chức và sự kiện <b>giả</b>, nên {@code ProductionHardening}
 * chặn {@code *.demo-data=true} ở production là hoàn toàn đúng. Nội dung ở
 * {@link StarterKnowledge} thì ngược lại: nó mô tả hành vi THẬT của hệ thống — giữ chỗ bao lâu,
 * hạn thanh toán bao nhiêu, nhận vé ở đâu — và đúng ở mọi môi trường. Đặt tên nó là
 * {@code demo-data} nghĩa là production luôn khởi động với kho tri thức rỗng, tức là đúng cái vấn
 * đề mà đường soạn kho tri thức sinh ra để giải.
 *
 * <h2>Nạp theo từng đoạn còn THIẾU</h2>
 *
 * <p>Điều kiện là trạng thái thật của kho, không phải một cờ "đã chạy": mỗi lần khởi động so
 * {@link StarterKnowledge#all()} với tiêu đề của các đoạn chung ({@code event_id is null}) đang có
 * và chỉ nhúng những đoạn còn thiếu. Bản trước nạp khi "kho trống" — tính cả đoạn của sự kiện — và
 * dừng ở lỗi nhúng đầu tiên, nên một lần Ollama khởi động chậm để lại kho thiếu VĨNH VIỄN (R-10).
 *
 * <p>Đánh đổi: một đoạn NỀN bị đội vận hành xoá sẽ quay lại ở lần khởi động sau. Đoạn họ tự soạn
 * (tiêu đề khác) không bao giờ bị đụng tới, và sửa nội dung một đoạn nền giữ nguyên tiêu đề thì
 * không bị ghi đè. Muốn bỏ hẳn tri thức nền thì tắt {@code starter-knowledge.enabled}.
 *
 * <h2>Hai replica cùng khởi động</h2>
 *
 * <p>Nạp dưới {@code pg_try_advisory_lock} giữ trên MỘT kết nối riêng suốt lúc nạp: khoá advisory
 * thuộc phiên, gọi qua {@code JdbcClient} thì lấy và nhả có thể rơi vào hai kết nối khác nhau của
 * pool. Không lấy được khoá nghĩa là replica khác đang nạp — thôi. Lọt khoá thì index
 * {@code uq_knowledge_global_title} (V0109) vẫn chặn bản trùng, và seeder bỏ qua đoạn đó (R-11).
 *
 * <h2>Chạy ngoài luồng khởi động</h2>
 *
 * <p>Mỗi đoạn là một lời gọi nhúng, và lần gọi đầu tiên tới Ollama còn phải nạp mô hình vào bộ
 * nhớ — cộng lại có thể là vài phút. {@link ApplicationRunner} chạy TRƯỚC khi ứng dụng báo sẵn
 * sàng, nên làm việc đó ngay tại đây sẽ đẩy readiness probe quá hạn và orchestrator giết container
 * đang hoàn toàn khoẻ mạnh. Vì vậy công việc được đẩy sang một luồng ảo và khởi động đi tiếp.
 *
 * <p>Hỏng thì ghi log rồi thôi. Mô hình nhúng chưa chạy là chuyện thường ở máy phát triển, và nó
 * không phải lý do để service từ chối phục vụ những đường không liên quan.
 */
@Component
@ConditionalOnProperty(
        prefix = "nexaticket.aichatbox.starter-knowledge",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KnowledgeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    /** Khoá advisory của seeder — "NXKS"; chỉ cần khác mọi khoá advisory khác trong cùng DB. */
    static final long ADVISORY_LOCK_KEY = 0x4E584B53L;

    private final KnowledgeBaseUseCase knowledge;
    private final JdbcClient db;
    private final DataSource dataSource;

    public KnowledgeSeeder(KnowledgeBaseUseCase knowledge, JdbcClient db, DataSource dataSource) {
        this.knowledge = knowledge;
        this.db = db;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("knowledge-seeder").start(this::seed);
    }

    /** Package-private: test gọi thẳng, không phải dựng thêm một application context chỉ để đợi một luồng nền. */
    void seed() {
        try {
            // Kho đã đủ thì không mượn kết nối, không lấy khoá, không gọi mô hình nhúng.
            if (missing().isEmpty()) {
                log.debug("Tri thức nền đã đủ, bỏ qua nạp");
                return;
            }
            try (Connection lockConnection = dataSource.getConnection()) {
                if (!advisory(lockConnection, "pg_try_advisory_lock")) {
                    log.debug("Replica khác đang nạp tri thức nền, bỏ qua");
                    return;
                }
                try {
                    load();
                } finally {
                    advisory(lockConnection, "pg_advisory_unlock");
                }
            }
        } catch (RuntimeException | SQLException e) {
            // Mô hình nhúng chưa chạy là chuyện thường ở máy phát triển. Trợ lý khi đó vẫn tra được
            // đơn hàng và vẫn chuyển được sang người thật — kém thông tin, không phải hỏng.
            log.warn("Không nạp được tri thức nền (mô hình nhúng chưa sẵn sàng?): {}", e.getMessage());
        }
    }

    /**
     * Nạp những đoạn còn thiếu, KHÔNG lấy khoá — package-private để test kiểm riêng chốt unique.
     * Tính lại danh sách thiếu: trong lúc chờ khoá, replica khác có thể đã nạp xong.
     */
    void load() {
        List<StarterKnowledge.Chunk> missing = missing();
        int total = StarterKnowledge.all().size();
        int added = 0;
        for (StarterKnowledge.Chunk chunk : missing) {
            try {
                knowledge.addChunk(null, chunk.title(), chunk.content());
                added++;
            } catch (ApiException e) {
                if (e.errorCode() != AiChatboxErrorCode.KNOWLEDGE_TITLE_TAKEN) {
                    stop(chunk, added, missing.size(), e);
                    return;
                }
                // Replica khác vừa nạp đúng đoạn này (lọt khoá) — index đã chặn bản trùng, đi tiếp.
            } catch (RuntimeException e) {
                stop(chunk, added, missing.size(), e);
                return;
            }
        }
        log.info("Đã nạp {} đoạn tri thức nền còn thiếu (kho có {} đoạn nền)", added, total);
    }

    private static void stop(StarterKnowledge.Chunk chunk, int added, int missing, RuntimeException e) {
        // DỪNG ở lỗi đầu tiên thay vì thử tiếp những đoạn còn lại.
        //
        // Mọi đoạn đi qua đúng một bộ nhúng, nên đoạn đầu hỏng vì nó chưa sẵn sàng thì các đoạn sau
        // hỏng y hệt. Thử tiếp chỉ biến một dòng WARN đọc được thành mười bốn dòng WARN kèm mười bốn
        // stack trace ở MỖI lần khởi động một máy chưa chạy Ollama. Phần còn thiếu được nạp tiếp ở
        // lần khởi động sau — điều kiện là "đoạn còn thiếu", không còn là "kho trống".
        log.warn(
                "Dừng nạp tri thức nền ở đoạn '{}' ({}/{} đoạn thiếu đã nạp): {}",
                chunk.title(),
                added,
                missing,
                e.getMessage());
    }

    /** Đoạn nền mà kho chưa có đoạn chung nào mang đúng tiêu đề. */
    private List<StarterKnowledge.Chunk> missing() {
        Set<String> present =
                new HashSet<>(db.sql("select title from event_knowledge_embeddings where event_id is null")
                        .query(String.class)
                        .list());
        return StarterKnowledge.all().stream()
                .filter(chunk -> !present.contains(chunk.title()))
                .toList();
    }

    private static boolean advisory(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select " + function + "(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
