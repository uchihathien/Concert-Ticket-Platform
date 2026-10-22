// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.port.RecipientDirectory;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

/**
 * Ba consumer của notification-service: message hỏng, thiếu trường hay không tra được người nhận
 * đều phải được <b>ack và bỏ qua</b> (không ném), vì giao lại mãi sẽ chặn đầu hàng đợi.
 */
class NotificationListenersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID EVENT = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID TICKET = UUID.fromString("33333333-3333-4333-a333-333333333333");

    private final QueueNotificationHandler queue = mock(QueueNotificationHandler.class);
    private final RecipientDirectory directory = mock(RecipientDirectory.class);

    private static Message message(String eventType, String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setMessageId(EVENT.toString())
                .setHeader("eventType", eventType)
                .setHeader("aggregateId", UUID.randomUUID().toString())
                .build();
    }

    private QueueNotificationHandler.Command queued() {
        ArgumentCaptor<QueueNotificationHandler.Command> captor =
                ArgumentCaptor.forClass(QueueNotificationHandler.Command.class);
        verify(queue).handle(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("IdentityEventsListener")
    class Identity {

        private final IdentityEventsListener listener = new IdentityEventsListener(queue, JSON);

        @Test
        @DisplayName("member.invited: người nhận là email trong payload, mẫu lời mời, headline là tên tổ chức")
        void loi_moi() {
            listener.onMemberInvited(
                    message("member.invited", "{\"email\":\"moi@example.com\",\"organizationName\":\"Acme\"}"));

            QueueNotificationHandler.Command cmd = queued();
            assertThat(cmd.recipient()).isEqualTo("moi@example.com");
            assertThat(cmd.template()).isEqualTo(EmailTemplate.ORGANIZATION_INVITATION);
            assertThat(cmd.headline()).isEqualTo("Acme");
            assertThat(cmd.eventId()).isEqualTo(EVENT);
            assertThat(cmd.eventType()).isEqualTo("member.invited");
        }

        @Test
        @DisplayName("organization.created: người nhận là ownerEmail, headline là name")
        void to_chuc_moi() {
            listener.onOrganizationCreated(
                    message("organization.created", "{\"ownerEmail\":\"chu@example.com\",\"name\":\"Nova\"}"));

            QueueNotificationHandler.Command cmd = queued();
            assertThat(cmd.recipient()).isEqualTo("chu@example.com");
            assertThat(cmd.headline()).isEqualTo("Nova");
        }

        @Test
        @DisplayName("thiếu tên tổ chức: vẫn gửi với headline null")
        void thieu_ten_van_gui() {
            listener.onMemberInvited(message("member.invited", "{\"email\":\"moi@example.com\"}"));

            assertThat(queued().headline()).isNull();
        }

        @Test
        @DisplayName("thiếu hoặc trống email: bỏ qua, không xếp thư, không ném")
        void thieu_email() {
            listener.onMemberInvited(message("member.invited", "{\"organizationName\":\"Acme\"}"));
            listener.onMemberInvited(message("member.invited", "{\"email\":\"  \"}"));

            verifyNoInteractions(queue);
        }

        @Test
        @DisplayName("message hỏng (không messageId, JSON sai): bỏ qua, không ném")
        void message_hong() {
            Message noId = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                    .build();

            listener.onMemberInvited(noId);
            listener.onMemberInvited(message("member.invited", "không phải json"));

            verifyNoInteractions(queue);
        }
    }

    @Nested
    @DisplayName("OrderEventsListener")
    class Orders {

        private final OrderEventsListener listener = new OrderEventsListener(queue, directory, JSON);

        private String body(String orderNumber) {
            return "{\"userId\":\"" + USER + "\""
                    + (orderNumber == null ? "" : ",\"orderNumber\":\"" + orderNumber + "\"") + "}";
        }

        @Test
        @DisplayName("order.paid: tra email từ identity theo userId, mẫu ORDER_PAID, headline là mã đơn")
        void don_da_thanh_toan() {
            when(directory.lookup(USER))
                    .thenReturn(Optional.of(new RecipientDirectory.Recipient("khach@example.com", "An")));

            listener.onOrderPaid(message("order.paid", body("NX-100")));

            QueueNotificationHandler.Command cmd = queued();
            assertThat(cmd.recipient()).isEqualTo("khach@example.com");
            assertThat(cmd.template()).isEqualTo(EmailTemplate.ORDER_PAID);
            assertThat(cmd.headline()).isEqualTo("NX-100");
        }

        @Test
        @DisplayName("order.expired: mẫu ORDER_EXPIRED, không lẫn với mẫu đã thanh toán")
        void don_het_han() {
            when(directory.lookup(USER))
                    .thenReturn(Optional.of(new RecipientDirectory.Recipient("khach@example.com", null)));

            listener.onOrderExpired(message("order.expired", body("NX-101")));

            assertThat(queued().template()).isEqualTo(EmailTemplate.ORDER_EXPIRED);
        }

        @Test
        @DisplayName("không tra được người nhận: bỏ qua, không xếp thư, không ném")
        void khong_co_nguoi_nhan() {
            when(directory.lookup(USER)).thenReturn(Optional.empty());

            listener.onOrderPaid(message("order.paid", body("NX-100")));

            verify(queue, never()).handle(any());
        }

        @Test
        @DisplayName("thiếu orderNumber, thiếu userId hoặc userId sai khuôn: bỏ qua và không hỏi identity")
        void thieu_truong() {
            listener.onOrderPaid(message("order.paid", body(null)));
            listener.onOrderPaid(message("order.paid", "{\"orderNumber\":\"NX-1\"}"));
            listener.onOrderPaid(message("order.paid", "{\"userId\":\"abc\",\"orderNumber\":\"NX-1\"}"));

            verifyNoInteractions(directory, queue);
        }

        @Test
        @DisplayName("message hỏng: bỏ qua, không ném")
        void message_hong() {
            listener.onOrderExpired(MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                    .build());

            verifyNoInteractions(queue);
        }
    }

    @Nested
    @DisplayName("TicketEventsListener")
    class Tickets {

        private final TicketEventsListener listener = new TicketEventsListener(queue, directory, JSON);

        private void recipientExists() {
            when(directory.lookup(USER))
                    .thenReturn(Optional.of(new RecipientDirectory.Recipient("khach@example.com", "An")));
        }

        private String body(String extra) {
            return "{\"ticketId\":\"" + TICKET + "\",\"userId\":\"" + USER + "\"" + extra + "}";
        }

        @Test
        @DisplayName("giờ check-in được in theo giờ Việt Nam (UTC+7), kèm tên sự kiện")
        void gio_viet_nam() {
            recipientExists();

            listener.onTicketCheckedIn(message(
                    "ticket.checked_in",
                    body(",\"eventTitle\":\"Đêm nhạc\",\"checkedInAt\":\"2026-10-12T12:42:00Z\"")));

            QueueNotificationHandler.Command cmd = queued();
            assertThat(cmd.headline()).isEqualTo("Đêm nhạc lúc 19:42 ngày 12/10/2026");
            assertThat(cmd.template()).isEqualTo(EmailTemplate.TICKET_CHECKED_IN);
        }

        @Test
        @DisplayName("khoá chống trùng suy từ vé: hai message khác id của cùng một vé cho cùng eventId")
        void khoa_chong_trung_theo_ve() {
            recipientExists();
            Message first = message("ticket.checked_in", body(""));
            Message second = MessageBuilder.withBody(body("").getBytes(StandardCharsets.UTF_8))
                    .setMessageId(UUID.randomUUID().toString())
                    .setHeader("eventType", "ticket.checked_in")
                    .build();

            listener.onTicketCheckedIn(first);
            listener.onTicketCheckedIn(second);

            ArgumentCaptor<QueueNotificationHandler.Command> captor =
                    ArgumentCaptor.forClass(QueueNotificationHandler.Command.class);
            verify(queue, org.mockito.Mockito.times(2)).handle(captor.capture());
            assertThat(captor.getAllValues().get(0).eventId())
                    .isEqualTo(captor.getAllValues().get(1).eventId())
                    .isNotEqualTo(EVENT);
        }

        @Test
        @DisplayName("thiếu tên sự kiện: dùng 'sự kiện'; thiếu giờ: chỉ có tên; giờ sai khuôn: chỉ có tên")
        void headline_du_phong() {
            recipientExists();

            listener.onTicketCheckedIn(message("ticket.checked_in", body("")));
            listener.onTicketCheckedIn(
                    message("ticket.checked_in", body(",\"eventTitle\":\"Show\",\"checkedInAt\":\"hôm qua\"")));

            ArgumentCaptor<QueueNotificationHandler.Command> captor =
                    ArgumentCaptor.forClass(QueueNotificationHandler.Command.class);
            verify(queue, org.mockito.Mockito.times(2)).handle(captor.capture());
            assertThat(captor.getAllValues().get(0).headline()).isEqualTo("sự kiện");
            assertThat(captor.getAllValues().get(1).headline()).isEqualTo("Show");
        }

        @Test
        @DisplayName("thiếu ticketId hoặc userId: bỏ qua")
        void thieu_truong() {
            listener.onTicketCheckedIn(message("ticket.checked_in", "{\"userId\":\"" + USER + "\"}"));
            listener.onTicketCheckedIn(message("ticket.checked_in", "{\"ticketId\":\"" + TICKET + "\"}"));

            verifyNoInteractions(directory, queue);
        }

        @Test
        @DisplayName("không tra được người nhận: bỏ qua")
        void khong_co_nguoi_nhan() {
            when(directory.lookup(USER)).thenReturn(Optional.empty());

            listener.onTicketCheckedIn(message("ticket.checked_in", body("")));

            verify(queue, never()).handle(any());
        }

        @Test
        @DisplayName("message hỏng: bỏ qua, không ném")
        void message_hong() {
            listener.onTicketCheckedIn(MessageBuilder.withBody("{".getBytes(StandardCharsets.UTF_8))
                    .build());

            verifyNoInteractions(queue);
        }
    }
}
