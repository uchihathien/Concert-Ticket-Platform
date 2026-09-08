// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.port.RecipientDirectory;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thư từ các sự kiện của ordering-service.
 *
 * <p>Khác listener của identity: sự kiện đơn hàng chỉ mang {@code userId}, không mang email. Đó là
 * cố ý — email nằm trong message nghĩa là email nằm luôn trong bảng outbox, thứ được giữ
 * <b>vĩnh viễn</b> làm nhật ký sự kiện (ADR-1009). Tra lúc cần thì dữ liệu cá nhân chỉ tồn tại ở
 * identity-service và trong bản ghi thư.
 *
 * <p>Cái giá là một lời gọi mạng cho mỗi thư, và nó có thể hỏng — khi đó ném ra để message được
 * giao lại, chứ không xếp hàng một thư không có người nhận.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final QueueNotificationHandler queue;
    private final RecipientDirectory recipients;
    private final ObjectMapper json;

    public OrderEventListener(QueueNotificationHandler queue, RecipientDirectory recipients, ObjectMapper json) {
        this.queue = queue;
        this.recipients = recipients;
        this.json = json;
    }

    @RabbitListener(queues = "notification.ordering.paid")
    @Transactional
    public void onOrderPaid(Message message) {
        queueFor(message, EmailTemplate.ORDER_PAID);
    }

    @RabbitListener(queues = "notification.ordering.expired")
    @Transactional
    public void onOrderExpired(Message message) {
        queueFor(message, EmailTemplate.ORDER_EXPIRED);
    }

    private void queueFor(Message message, EmailTemplate template) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message ordering hỏng: {}", e.getMessage());
            return;
        }

        UUID userId = event.uuid("userId");
        if (userId == null) {
            log.error("Message {} thiếu userId, bỏ qua", event.eventType());
            return;
        }

        // Ném ra thì transaction rollback và message được giao lại. Xếp hàng một thư không có
        // người nhận sẽ tạo ra một bản ghi vĩnh viễn không bao giờ gửi được.
        var recipient = recipients.lookup(userId);

        queue.handle(new QueueNotificationHandler.Command(
                event.eventId(), event.eventType(), recipient.email(), template, event.text("orderNumber")));
    }
}
