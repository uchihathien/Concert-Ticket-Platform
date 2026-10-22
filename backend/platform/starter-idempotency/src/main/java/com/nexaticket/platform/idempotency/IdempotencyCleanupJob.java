// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dọn {@code idempotency_records} cũ (ADR-0007: giữ ≥ 24 giờ, không giữ mãi).
 *
 * <p>Không có job này bảng lớn dần theo số lần đặt vé / giữ chỗ — mỗi request có khoá thêm một dòng kèm cả
 * câu trả lời JSON. Nhiều instance cùng chạy cũng an toàn: {@code DELETE} theo điều kiện thời gian là idempotent.
 */
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    /** Chặn vòng lặp vô hạn nếu có thứ gì đó chèn nhanh hơn ta xoá; vòng sau sẽ dọn tiếp. */
    static final int MAX_BATCHES_PER_RUN = 50;

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;

    public IdempotencyCleanupJob(IdempotencyStore store, IdempotencyProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${nexaticket.idempotency.cleanup-interval:PT1H}",
            initialDelayString = "${nexaticket.idempotency.cleanup-interval:PT1H}")
    public void scheduled() {
        runOnce();
    }

    /** @return tổng số dòng đã xoá trong lần chạy này */
    public int runOnce() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = store.purgeOlderThan(properties.retention(), properties.cleanupBatch());
            total += deleted;
            if (deleted < properties.cleanupBatch()) {
                break;
            }
        }
        if (total > 0) {
            log.info("Đã dọn {} bản ghi idempotency cũ hơn {}", total, properties.retention());
        }
        return total;
    }
}
