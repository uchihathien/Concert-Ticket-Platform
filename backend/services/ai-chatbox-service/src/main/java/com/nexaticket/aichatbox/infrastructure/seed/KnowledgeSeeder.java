// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.seed;

import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Nạp tri thức nền khi kho còn trống.
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
 * <h2>Chỉ nạp khi kho TRỐNG</h2>
 *
 * <p>Không phải "nạp rồi bỏ qua theo một cờ đã chạy": điều kiện là trạng thái thật của kho. Nhờ
 * vậy đội vận hành sửa hoặc xoá đoạn nào cũng được mà lần khởi động sau không dựng lại chúng —
 * một seeder ghi đè công của người khác là một seeder người ta sẽ tắt đi rồi quên bật lại.
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

    private final KnowledgeBaseUseCase knowledge;

    public KnowledgeSeeder(KnowledgeBaseUseCase knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("knowledge-seeder").start(this::seed);
    }

    /** Package-private: test gọi thẳng, không phải dựng thêm một application context chỉ để đợi một luồng nền. */
    void seed() {
        try {
            if (!knowledge.listChunks(null, 1, 0).isEmpty()) {
                log.debug("Kho tri thức đã có nội dung, bỏ qua nạp nền");
                return;
            }

            int added = 0;
            for (StarterKnowledge.Chunk chunk : StarterKnowledge.all()) {
                // Một đoạn hỏng không được làm hỏng cả mẻ: mười ba đoạn đúng vẫn hơn không đoạn nào.
                try {
                    knowledge.addChunk(null, chunk.title(), chunk.content());
                    added++;
                } catch (RuntimeException e) {
                    log.warn("Không nạp được đoạn tri thức nền '{}': {}", chunk.title(), e.getMessage());
                }
            }
            log.info(
                    "Đã nạp {}/{} đoạn tri thức nền",
                    added,
                    StarterKnowledge.all().size());

        } catch (RuntimeException e) {
            // Mô hình nhúng chưa chạy là chuyện thường ở máy phát triển. Trợ lý khi đó vẫn tra được
            // đơn hàng và vẫn chuyển được sang người thật — kém thông tin, không phải hỏng.
            log.warn("Không nạp được tri thức nền (mô hình nhúng chưa sẵn sàng?): {}", e.getMessage());
        }
    }
}
