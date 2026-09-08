// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.job;

import com.nexaticket.notification.application.command.DispatchNotificationsJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gửi thư đang chờ mỗi 10 giây. Tắt được để test tự điều khiển thời điểm. */
@Component
@EnableScheduling
@ConditionalOnProperty(value = "nexaticket.notification.workers.enabled", matchIfMissing = true)
public class NotificationScheduler {

    private final DispatchNotificationsJob dispatch;

    public NotificationScheduler(DispatchNotificationsJob dispatch) {
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${nexaticket.notification.workers.interval:10s}")
    public void dispatch() {
        dispatch.runOnce();
    }
}
