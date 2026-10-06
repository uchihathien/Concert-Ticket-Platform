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
 * Xếp hàng thư cho khách khi đơn đổi trạng thái.
 *
 * <p>Trước consumer này, {@link QueueNotificationHandler} chỉ được test gọi: khách trả tiền xong
 * không nhận được thư nào, và đơn hết hạn cũng vậy.
 *
 * <p>Hai hàng đợi riêng chứ không một queue {@code "#"}, vì hai routing key dẫn tới hai mẫu thư
 * khác hẳn nhau — "đơn của bạn đã thanh toán" và "đơn của bạn đã hết hạn" không được nhầm chỗ. Đó
 * cũng chính là lý do topology tách {@code order.paid} và {@code order.expired} thành hai queue.
 *
 * <p>Người nhận tra từ identity-service <b>đúng lúc gửi</b>, không đọc từ payload: sự kiện mang
 * {@code userId} chứ không mang email, để dữ liệu cá nhân không nằm rải trong mọi hàng đợi và mọi
 * bản sao lưu của broker.
 *
 * <p>Chỉ ghi vào database rồi trả về — {@code DispatchNotificationsJob} mới thật sự gửi. Một máy
 * chủ SMTP chậm ở đây sẽ làm nghẽn cả hàng đợi.
 */
@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private static final String PAID_QUEUE = "notification.ordering.paid";
    private static final String EXPIRED_QUEUE = "notification.ordering.expired";

    private final QueueNotificationHandler queueNotification;
    private final RecipientDirectory recipients;
    private final ObjectMapper json;

    public OrderEventsListener(
            QueueNotificationHandler queueNotification, RecipientDirectory recipients, ObjectMapper json) {
        this.queueNotification = queueNotification;
        this.recipients = recipients;
        this.json = json;
    }

    @RabbitListener(queues = PAID_QUEUE)
    @Transactional
    public void onOrderPaid(Message message) {
        queue(message, EmailTemplate.ORDER_PAID);
    }

    @RabbitListener(queues = EXPIRED_QUEUE)
    @Transactional
    public void onOrderExpired(Message message) {
        queue(message, EmailTemplate.ORDER_EXPIRED);
    }

    private void queue(Message message, EmailTemplate template) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message ordering hỏng: {}", e.getMessage());
            return;
        }

        UUID userId = event.uuid("userId");
        String orderNumber = event.text("orderNumber");
        if (userId == null || orderNumber == null) {
            log.error("Message {} thiếu userId hoặc orderNumber, không xếp được thư", event.eventType());
            return;
        }

        var recipient = recipients.lookup(userId).orElse(null);
        if (recipient == null) {
            // Ack chứ không nack: người dùng có thể đã bị xoá, và giao lại mãi một message không
            // gửi được sẽ chặn đầu hàng đợi — mọi thư phía sau im lặng biến mất theo.
            log.warn("Không có địa chỉ nhận thư cho sự kiện {}, bỏ qua", event.eventId());
            return;
        }

        boolean queued = queueNotification.handle(new QueueNotificationHandler.Command(
                event.eventId(), event.eventType(), recipient.email(), template, orderNumber));
        if (queued) {
            log.info("Đã xếp thư {} cho đơn {}", template, orderNumber);
        }
    }
}
