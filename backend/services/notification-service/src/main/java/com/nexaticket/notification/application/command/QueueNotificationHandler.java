// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.application.command;

import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.model.Notification;
import com.nexaticket.notification.domain.port.NotificationRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận một sự kiện và xếp hàng thư tương ứng.
 *
 * <p>Chỉ ghi vào database rồi trả về — <b>không</b> gửi thư ngay trong consumer. Máy chủ SMTP chậm
 * sẽ làm nghẽn cả consumer và message dồn lại trong RabbitMQ; tách ra thành hàng đợi riêng có
 * retry thì SMTP chậm chỉ làm thư tới muộn.
 *
 * <p>Consumer <b>không phụ thuộc thứ tự</b> (ADR-1009): mỗi sự kiện sinh đúng một thư độc lập, nên
 * {@code order.paid} đến trước {@code order.created} cũng không sao.
 */
@Service
public class QueueNotificationHandler {

    private static final Logger log = LoggerFactory.getLogger(QueueNotificationHandler.class);

    private final NotificationRepository notifications;

    public QueueNotificationHandler(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * @param eventId id sự kiện, dùng làm khoá chống trùng
     * @param headline tên hiển thị trong tiêu đề thư: mã đơn, tên sự kiện, tên tổ chức
     */
    public record Command(UUID eventId, String eventType, String recipient, EmailTemplate template, String headline) {}

    /** @return true nếu đây là lần đầu thấy sự kiện này */
    @Transactional
    public boolean handle(Command cmd) {
        Map<String, Object> payload = Map.of("headline", cmd.headline() == null ? "" : cmd.headline());
        boolean queued = notifications.queueIfNew(
                Notification.queue(cmd.eventId(), cmd.eventType(), cmd.recipient(), cmd.template(), payload));
        if (!queued) {
            // Không log địa chỉ email: log của hệ thống không phải chỗ chứa dữ liệu cá nhân.
            log.debug("Sự kiện {} đã xử lý trước đó, bỏ qua", cmd.eventId());
        }
        return queued;
    }
}
