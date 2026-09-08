// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.job;

import com.nexaticket.payment.application.command.ExpireIntentsJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Đóng các yêu cầu thanh toán quá hạn. Tắt được để integration test tự điều khiển thời điểm. */
@Component
@EnableScheduling
@ConditionalOnProperty(value = "nexaticket.payment.workers.enabled", matchIfMissing = true)
public class PaymentSchedulers {

    private final ExpireIntentsJob expireIntents;

    public PaymentSchedulers(ExpireIntentsJob expireIntents) {
        this.expireIntents = expireIntents;
    }

    @Scheduled(fixedDelayString = "${nexaticket.payment.workers.expire-interval:15s}")
    public void expireIntents() {
        expireIntents.runOnce();
    }
}
