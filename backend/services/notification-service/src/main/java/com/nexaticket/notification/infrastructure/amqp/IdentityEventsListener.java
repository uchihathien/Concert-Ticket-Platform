// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.port.RecipientDirectory;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thư của luồng tổ chức: lời mời thành viên, tổ chức vừa được tạo, và thành viên vừa được thêm.
 *
 * <p>{@code member.added} khác hai sự kiện kia: người nhận là một tài khoản ĐÃ CÓ, nên payload chỉ
 * mang {@code userId} và địa chỉ được tra ở identity lúc xếp thư — cùng cách với luồng đơn hàng.
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
    private static final String ADDED_QUEUE = "notification.identity.member-added";

    /** Tên vai trò trong thư — người nhận không đọc mã enum. */
    private static final Map<String, String> ROLE_LABELS = Map.of(
            "ORG_OWNER", "Chủ tổ chức",
            "ORG_ADMIN", "Quản trị tổ chức",
            "EVENT_MANAGER", "Quản lý sự kiện",
            "CHECKIN_STAFF", "Staff (soát vé)");

    private final QueueNotificationHandler queueNotification;
    private final RecipientDirectory recipients;
    private final ObjectMapper json;

    public IdentityEventsListener(
            QueueNotificationHandler queueNotification, RecipientDirectory recipients, ObjectMapper json) {
        this.queueNotification = queueNotification;
        this.recipients = recipients;
        this.json = json;
    }

    /** "Bạn đã được thêm vào tổ chức X với vai Staff" — gửi cho chính người được thêm. */
    @RabbitListener(queues = ADDED_QUEUE)
    @Transactional
    public void onMemberAdded(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message identity hỏng: {}", e.getMessage());
            return;
        }

        UUID userId = event.uuid("userId");
        if (userId == null) {
            log.error("Message {} thiếu userId, không xếp được thư", event.eventType());
            return;
        }
        var recipient = recipients.lookup(userId).orElse(null);
        if (recipient == null) {
            // Ack chứ không nack, cùng lý do với luồng đơn hàng: giao lại mãi một message không gửi
            // được sẽ chặn đầu hàng đợi.
            log.warn("Không có địa chỉ nhận thư cho sự kiện {}, bỏ qua", event.eventId());
            return;
        }

        String role = event.text("role");
        boolean queued = queueNotification.handle(new QueueNotificationHandler.Command(
                event.eventId(),
                event.eventType(),
                recipient.email(),
                EmailTemplate.MEMBER_ADDED,
                event.text("organizationName"),
                ROLE_LABELS.getOrDefault(role, "thành viên")));
        if (queued) {
            log.info("Đã xếp thư báo thêm thành viên cho sự kiện {}", event.eventType());
        }
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
