// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.application.command;

import com.nexaticket.notification.domain.model.Notification;
import com.nexaticket.notification.domain.port.EmailSender;
import com.nexaticket.notification.domain.port.NotificationRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gửi các thư đang chờ.
 *
 * <p>Một lần gửi hỏng <b>không</b> được làm hỏng cả lô: mỗi thư được ghi kết quả riêng. Nếu ném ra
 * ngoài vòng lặp thì một địa chỉ email sai sẽ chặn toàn bộ hàng đợi, và mọi khách khác không nhận
 * được gì.
 */
@Service
public class DispatchNotificationsJob {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationsJob.class);
    private static final int BATCH_SIZE = 100;

    private final NotificationRepository notifications;
    private final EmailSender sender;
    private final Clock clock;

    public DispatchNotificationsJob(NotificationRepository notifications, EmailSender sender, Clock clock) {
        this.notifications = notifications;
        this.sender = sender;
        this.clock = clock;
    }

    /** @return số thư gửi thành công ở lượt này */
    @Transactional
    public int runOnce() {
        List<Notification> due = notifications.claimDue(clock.instant(), BATCH_SIZE);
        int sent = 0;
        for (Notification notification : due) {
            try {
                sender.send(notification.recipient(), notification.subject(), notification.body());
                notification.markSent(clock.instant());
                sent++;
            } catch (RuntimeException e) {
                notification.markFailed(e.toString(), clock.instant());
                log.warn(
                        "Gửi thư {} hỏng lần {} ({})",
                        notification.id(),
                        notification.attempts(),
                        notification.status());
            }
            notifications.update(notification);
        }
        return sent;
    }
}
