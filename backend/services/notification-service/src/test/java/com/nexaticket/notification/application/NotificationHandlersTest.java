// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexaticket.notification.application.command.DispatchNotificationsJob;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.model.Notification;
import com.nexaticket.notification.domain.model.NotificationStatus;
import com.nexaticket.notification.domain.port.EmailSender;
import com.nexaticket.notification.domain.port.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link QueueNotificationHandler} và {@link DispatchNotificationsJob} với repository và sender giả. */
class NotificationHandlersTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final EmailSender sender = mock(EmailSender.class);

    private static Notification pending(String recipient) {
        return Notification.queue(
                UUID.randomUUID(), "order.paid", recipient, EmailTemplate.ORDER_PAID, Map.of("headline", "NX-1"));
    }

    @Nested
    @DisplayName("QueueNotificationHandler")
    class Queue {

        private final QueueNotificationHandler handler = new QueueNotificationHandler(repository);

        @Test
        @DisplayName("sự kiện mới: xếp một thư PENDING với đúng người nhận, mẫu và eventId")
        void su_kien_moi() {
            when(repository.queueIfNew(any())).thenReturn(true);
            UUID event = UUID.randomUUID();

            boolean queued = handler.handle(new QueueNotificationHandler.Command(
                    event, "order.paid", "khach@example.com", EmailTemplate.ORDER_PAID, "NX-77"));

            assertThat(queued).isTrue();
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(repository).queueIfNew(captor.capture());
            Notification n = captor.getValue();
            assertThat(n.eventId()).isEqualTo(event);
            assertThat(n.recipient()).isEqualTo("khach@example.com");
            assertThat(n.template()).isEqualTo(EmailTemplate.ORDER_PAID);
            assertThat(n.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.subject()).contains("NX-77");
        }

        @Test
        @DisplayName("sự kiện trùng (repository trả false): handler trả false, không ném")
        void su_kien_trung() {
            when(repository.queueIfNew(any())).thenReturn(false);

            boolean queued = handler.handle(new QueueNotificationHandler.Command(
                    UUID.randomUUID(), "order.paid", "a@b.c", EmailTemplate.ORDER_PAID, "NX-1"));

            assertThat(queued).isFalse();
        }

        @Test
        @DisplayName("headline null: dùng chuỗi rỗng, không NPE trong Map.of")
        void headline_null() {
            when(repository.queueIfNew(any())).thenReturn(true);

            handler.handle(new QueueNotificationHandler.Command(
                    UUID.randomUUID(), "order.paid", "a@b.c", EmailTemplate.ORDER_EXPIRED, null));

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(repository).queueIfNew(captor.capture());
            assertThat(captor.getValue().payload()).containsEntry("headline", "");
        }

        @Test
        @DisplayName("không bao giờ gọi sender: gửi là việc của job, không nằm trong consumer")
        void khong_gui_trong_consumer() {
            when(repository.queueIfNew(any())).thenReturn(true);

            handler.handle(new QueueNotificationHandler.Command(
                    UUID.randomUUID(), "order.paid", "a@b.c", EmailTemplate.ORDER_PAID, "NX"));

            verify(sender, never()).send(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("DispatchNotificationsJob")
    class Dispatch {

        private final DispatchNotificationsJob job = new DispatchNotificationsJob(repository, sender, clock);

        @Test
        @DisplayName("không có thư đến hạn: trả 0, không gửi gì")
        void khong_co_thu() {
            when(repository.claimDue(any(), anyInt())).thenReturn(List.of());

            assertThat(job.runOnce()).isZero();
            verify(sender, never()).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("hỏi đúng thời điểm hiện tại và lô 100")
        void hoi_dung_tham_so() {
            when(repository.claimDue(any(), anyInt())).thenReturn(List.of());

            job.runOnce();

            verify(repository).claimDue(NOW, 100);
        }

        @Test
        @DisplayName("gửi thành công: markSent, lưu lại, đếm vào kết quả")
        void gui_thanh_cong() {
            Notification n = pending("khach@example.com");
            when(repository.claimDue(any(), anyInt())).thenReturn(List.of(n));

            int sent = job.runOnce();

            assertThat(sent).isEqualTo(1);
            assertThat(n.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.sentAt()).isEqualTo(NOW);
            verify(sender).send(eq("khach@example.com"), eq(n.subject()), eq(n.body()));
            verify(repository).update(n);
        }

        @Test
        @DisplayName("một thư hỏng không chặn cả lô: thư sau vẫn được gửi, thư hỏng được lên lịch thử lại")
        void mot_thu_hong_khong_chan_lo() {
            Notification bad = pending("sai-dia-chi");
            Notification good = pending("tot@example.com");
            when(repository.claimDue(any(), anyInt())).thenReturn(List.of(bad, good));
            doThrow(new EmailSender.SendFailedException("smtp 550", null))
                    .when(sender)
                    .send(eq("sai-dia-chi"), anyString(), anyString());

            int sent = job.runOnce();

            assertThat(sent).isEqualTo(1);
            assertThat(bad.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(bad.attempts()).isEqualTo(1);
            assertThat(bad.nextRetryAt()).isAfter(NOW);
            assertThat(bad.lastError()).contains("smtp 550");
            assertThat(good.status()).isEqualTo(NotificationStatus.SENT);
            verify(repository, times(2)).update(any());
        }

        @Test
        @DisplayName("lỗi thứ năm: thư thành DEAD và vẫn được lưu")
        void loi_thu_nam_thanh_dead() {
            Notification n = Notification.rehydrate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "order.paid",
                    "a@b.c",
                    EmailTemplate.ORDER_PAID,
                    Map.of("headline", "NX"),
                    NotificationStatus.PENDING,
                    4);
            when(repository.claimDue(any(), anyInt())).thenReturn(List.of(n));
            doThrow(new RuntimeException("down")).when(sender).send(anyString(), anyString(), anyString());

            int sent = job.runOnce();

            assertThat(sent).isZero();
            assertThat(n.status()).isEqualTo(NotificationStatus.DEAD);
            verify(repository).update(n);
        }
    }
}
