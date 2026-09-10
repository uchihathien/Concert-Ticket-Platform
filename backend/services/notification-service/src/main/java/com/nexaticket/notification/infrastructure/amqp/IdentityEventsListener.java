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
 * Thư của luồng tổ chức: lời mời thành viên, và tổ chức vừa được tạo.
 *
 * <p>Hai hàng đợi này đã có trong topology từ đầu nhưng <b>chưa từng có consumer</b>: 32 message nằm
 * lại trong broker ở môi trường dev, và với người dùng thì triệu chứng là "mời đồng nghiệp xong họ
 * không nhận được gì". Lời mời vẫn hợp lệ trong database — chỉ là không ai được báo.
 *
 * <p>Người nhận nằm sẵn trong payload ({@code email} / {@code ownerEmail}), khác với luồng đơn hàng
 * phải tra qua identity-service. Đó là chủ đích của bên phát: một lời mời <b>là</b> một địa chỉ email
 * chưa gắn với người dùng nào — chưa chắc đã có tài khoản để mà tra.
 *
 * <p><b>Không gửi token lời mời trong thư.</b> Payload cũng không mang nó. Token nằm mãi trong hộp thư
 * và trong mọi bản sao lưu hộp thư đó; mẫu thư chỉ dẫn người ta mở ứng dụng.
 */
@Component
public class IdentityEventsListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventsListener.class);
    private static final String INVITED_QUEUE = "notification.identity.member-invited";
    private static final String ORG_CREATED_QUEUE = "notification.identity.organization-created";

    private final QueueNotificationHandler queueNotification;
    private final ObjectMapper json;

    public IdentityEventsListener(QueueNotificationHandler queueNotification, ObjectMapper json) {
        this.queueNotification = queueNotification;
        this.json = json;
    }

    @RabbitListener(queues = INVITED_QUEUE)
    @Transactional
    public void onMemberInvited(Message message) {
        queue(message, "email", "organizationName");
    }

    /** Chủ sở hữu tổ chức mới cũng nhận đúng lời mời đó — họ phải chấp nhận thì mới vào được. */
    @RabbitListener(queues = ORG_CREATED_QUEUE)
    @Transactional
    public void onOrganizationCreated(Message message) {
        queue(message, "ownerEmail", "name");
    }

    private void queue(Message message, String recipientField, String headlineField) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message identity hỏng: {}", e.getMessage());
            return;
        }

        String recipient = event.text(recipientField);
        if (recipient == null || recipient.isBlank()) {
            // Ack chứ không nack: thiếu địa chỉ thì lần giao lại thứ hai mươi cũng vẫn thiếu, và một
            // message không xử lý được sẽ chặn đầu hàng đợi — mọi thư phía sau im lặng biến mất.
            log.error("Message {} thiếu {}, không xếp được thư", event.eventType(), recipientField);
            return;
        }

        // Tên tổ chức chỉ để dựng tiêu đề. Thiếu thì vẫn gửi: một thư có tiêu đề trống vẫn tới nơi,
        // còn không gửi thì người được mời không bao giờ biết mình được mời.
        String headline = event.text(headlineField);
        boolean queued = queueNotification.handle(new QueueNotificationHandler.Command(
                event.eventId(), event.eventType(), recipient, EmailTemplate.ORGANIZATION_INVITATION, headline));
        if (queued) {
            log.info("Đã xếp thư mời cho sự kiện {}", event.eventType());
        }
    }
}
