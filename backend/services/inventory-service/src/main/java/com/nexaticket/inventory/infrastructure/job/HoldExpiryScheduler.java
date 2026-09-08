// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.job;

import com.nexaticket.inventory.application.command.ExpireHoldsJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chạy worker dọn giữ chỗ hết hạn mỗi 10 giây.
 *
 * <p>Tách khỏi {@link ExpireHoldsJob} để test gọi thẳng {@code runOnce()} và khẳng định kết quả,
 * thay vì phải chờ đồng hồ.
 *
 * <p>Tắt được bằng {@code nexaticket.inventory.hold-expiry.enabled=false} — integration test tự
 * điều khiển thời điểm chạy, worker chạy ngầm sẽ làm kết quả test bập bênh.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(value = "nexaticket.inventory.hold-expiry.enabled", matchIfMissing = true)
public class HoldExpiryScheduler {

    private final ExpireHoldsJob job;

    public HoldExpiryScheduler(ExpireHoldsJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${nexaticket.inventory.hold-expiry.interval:10s}")
    public void run() {
        job.runOnce();
    }
}
