// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.job;

import com.nexaticket.ordering.application.command.CompensationSweeper;
import com.nexaticket.ordering.application.command.ExpireOrdersJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hai worker của Ordering.
 *
 * <p>Tách khỏi lớp nghiệp vụ để test gọi thẳng {@code runOnce()} và khẳng định kết quả, thay vì
 * phải chờ đồng hồ. Tắt được bằng cấu hình vì worker chạy ngầm sẽ làm integration test bập bênh.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(value = "nexaticket.ordering.workers.enabled", matchIfMissing = true)
public class OrderingSchedulers {

    private final ExpireOrdersJob expireOrders;
    private final CompensationSweeper compensationSweeper;

    public OrderingSchedulers(ExpireOrdersJob expireOrders, CompensationSweeper compensationSweeper) {
        this.expireOrders = expireOrders;
        this.compensationSweeper = compensationSweeper;
    }

    /** Lưới an toàn thứ ba: đơn quá hạn thì nhả ghế, dù mọi cơ chế khác đã hỏng. */
    @Scheduled(fixedDelayString = "${nexaticket.ordering.workers.expire-interval:15s}")
    public void expireOrders() {
        expireOrders.runOnce();
    }

    /** Lưới an toàn thứ hai: bù trừ lại các saga mà bù trừ đồng bộ đã hỏng. */
    @Scheduled(fixedDelayString = "${nexaticket.ordering.workers.sweep-interval:30s}")
    public void sweepCompensations() {
        compensationSweeper.runOnce();
    }
}
