// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.port.RecipientDirectory;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thư báo "vé đã check-in" cho khách.
 *
 * <p>Nằm ngoài đường soát vé: ticketing ghi sự kiện vào outbox rồi trả kết quả cho nhân viên ngay.
 * Listener này chỉ <b>xếp</b> thư vào bảng {@code notifications}; gửi, thử lại và đánh dấu chết là
 * việc của {@code DispatchNotificationsJob}. SMTP sập không chặn ai ở cửa.
 */
@Component
public class TicketEventsListener {

    private static final Logger log = LoggerFactory.getLogger(TicketEventsListener.class);
    private static final String CHECKED_IN_QUEUE = "notification.ticketing.checked-in";

    /** Giờ in trong thư là giờ ở nơi diễn ra sự kiện, không phải giờ của máy chủ. */
    private static final DateTimeFormatter CHECKIN_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final QueueNotificationHandler queueNotification;
    private final RecipientDirectory recipients;
    private final ObjectMapper json;

    public TicketEventsListener(
            QueueNotificationHandler queueNotification, RecipientDirectory recipients, ObjectMapper json) {
        this.queueNotification = queueNotification;
        this.recipients = recipients;
        this.json = json;
    }

    @RabbitListener(queues = CHECKED_IN_QUEUE)
    @Transactional
    public void onTicketCheckedIn(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message ticketing hỏng: {}", e.getMessage());
            return;
        }

        UUID ticketId = event.uuid("ticketId");
        UUID userId = event.uuid("userId");
        if (ticketId == null || userId == null) {
            log.error("Message {} thiếu ticketId hoặc userId, không xếp được thư", event.eventType());
            return;
        }

        var recipient = recipients.lookup(userId).orElse(null);
        if (recipient == null) {
            log.warn("Không có địa chỉ nhận thư cho sự kiện {}, bỏ qua", event.eventId());
            return;
        }

        // Khoá chống trùng suy từ VÉ, không phải từ message. `notifications.event_id` là UNIQUE,
        // nên dù ticketing có phát sự kiện hai lần cho cùng một vé thì cũng chỉ một thư được xếp.
        UUID oncePerTicket = UUID.nameUUIDFromBytes(("ticket.checked_in:" + ticketId).getBytes(StandardCharsets.UTF_8));

        boolean queued = queueNotification.handle(new QueueNotificationHandler.Command(
                oncePerTicket, event.eventType(), recipient.email(), EmailTemplate.TICKET_CHECKED_IN, headline(event)));
        if (queued) {
            log.info("Đã xếp thư check-in cho vé {}", ticketId);
        }
    }

    /** "<tên sự kiện> lúc 19:42 ngày 12/10/2026" — thứ điền vào cả tiêu đề lẫn nội dung thư. */
    private static String headline(ConsumedEvent event) {
        String title = event.text("eventTitle");
        String where = title == null || title.isBlank() ? "sự kiện" : title;
        String at = event.text("checkedInAt");
        if (at == null) {
            return where;
        }
        try {
            return where + " lúc " + CHECKIN_TIME.format(Instant.parse(at));
        } catch (DateTimeParseException e) {
            return where;
        }
    }
}
