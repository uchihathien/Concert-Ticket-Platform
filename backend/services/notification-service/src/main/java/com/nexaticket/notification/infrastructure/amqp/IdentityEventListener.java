// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thư từ các sự kiện của identity-service.
 *
 * <p>Hai sự kiện này <b>đã mang sẵn email</b> trong payload, nên không cần tra thêm: lời mời vốn
 * được gửi <i>tới một địa chỉ email</i>, địa chỉ đó là nội dung của chính sự kiện chứ không phải
 * dữ liệu tra cứu thêm.
 */
@Component
public class IdentityEventListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventListener.class);

    private final QueueNotificationHandler queue;
    private final ObjectMapper json;

    public IdentityEventListener(QueueNotificationHandler queue, ObjectMapper json) {
        this.queue = queue;
        this.json = json;
    }

    @RabbitListener(queues = "notification.identity.organization-created")
    @Transactional
    public void onOrganizationCreated(Message message) {
        ConsumedEvent event = parse(message);
        if (event == null) {
            return;
        }
        queue.handle(new QueueNotificationHandler.Command(
                event.eventId(),
                event.eventType(),
                event.text("ownerEmail"),
                EmailTemplate.ORGANIZATION_INVITATION,
                event.text("name")));
    }

    @RabbitListener(queues = "notification.identity.member-invited")
    @Transactional
    public void onMemberInvited(Message message) {
        ConsumedEvent event = parse(message);
        if (event == null) {
            return;
        }
        queue.handle(new QueueNotificationHandler.Command(
                event.eventId(),
                event.eventType(),
                event.text("email"),
                EmailTemplate.ORGANIZATION_INVITATION,
                event.text("organizationId")));
    }

    /** @return null nếu message hỏng — đã log, và caller ack để không chặn queue */
    private ConsumedEvent parse(Message message) {
        try {
            return ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message identity hỏng: {}", e.getMessage());
            return null;
        }
    }
}
