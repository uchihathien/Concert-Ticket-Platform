// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.notification.application.command.DispatchNotificationsJob;
import com.nexaticket.notification.application.command.QueueNotificationHandler;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.model.NotificationStatus;
import com.nexaticket.notification.domain.port.EmailSender;
import com.nexaticket.notification.domain.port.NotificationRepository;
import com.nexaticket.platform.test.PostgresSingleton;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Xếp hàng và gửi thư.
 *
 * <p>Máy chủ mail được thay bằng hàng giả điều khiển được: thứ cần kiểm ở đây là <b>hành vi khi
 * gửi hỏng</b> — retry, backoff, và bỏ cuộc đúng lúc — chứ không phải khả năng nói chuyện SMTP.
 */
@SpringBootTest(properties = "nexaticket.notification.workers.enabled=false")
@ActiveProfiles("test")
@Import(NotificationIT.FakeMail.class)
class NotificationIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("notification_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @TestConfiguration
    static class FakeMail {

        @Bean
        @Primary
        Fake fakeSender() {
            return new Fake();
        }

        static class Fake implements EmailSender {

            final List<String> sent = new ArrayList<>();
            boolean broken;

            @Override
            public void send(String recipient, String subject, String body) {
                if (broken) {
                    throw new SendFailedException("máy chủ mail hỏng giả lập", null);
                }
                sent.add(subject);
            }
        }
    }

    @Autowired
    QueueNotificationHandler queue;

    @Autowired
    DispatchNotificationsJob dispatch;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    FakeMail.Fake mail;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE notifications");
        mail.sent.clear();
        mail.broken = false;
    }

    @Test
    @DisplayName("Cùng một sự kiện đến hai lần: chỉ gửi một thư")
    void su_kien_trung_chi_gui_mot_thu() {
        // RabbitMQ giao ít nhất một lần. Không có chốt chặn này thì khách nhận ba email
        // "đơn hàng đã thanh toán" cho một lần mua.
        UUID eventId = UUID.randomUUID();

        assertThat(queue.handle(command(eventId))).isTrue();
        assertThat(queue.handle(command(eventId))).isFalse();

        dispatch.runOnce();
        assertThat(mail.sent).hasSize(1);
    }

    @Test
    @DisplayName("Gửi hỏng thì thử lại, không chuyển sang SENT")
    void gui_hong_thi_thu_lai() {
        mail.broken = true;
        queue.handle(command(UUID.randomUUID()));

        assertThat(dispatch.runOnce()).isZero();

        assertThat(notifications.countByStatus(NotificationStatus.PENDING)).isEqualTo(1);
        assertThat(notifications.countByStatus(NotificationStatus.SENT)).isZero();
    }

    @Test
    @DisplayName("Hỏng đủ năm lần thì vào hàng đợi chết, không thử mãi")
    void hong_du_nam_lan_thi_bo_cuoc() {
        // Thử mãi một địa chỉ email sai là lãng phí và là cách nhanh nhất để bị nhà cung cấp
        // email chặn. Nhưng thư chết phải có người nhìn, không được im lặng bỏ qua.
        mail.broken = true;
        queue.handle(command(UUID.randomUUID()));

        for (int i = 0; i < 5; i++) {
            // Xoá backoff để không phải chờ thật 1+2+4+8 phút.
            jdbc.update("UPDATE notifications SET next_retry_at = now() - interval '1 hour'");
            dispatch.runOnce();
        }

        assertThat(notifications.countByStatus(NotificationStatus.DEAD)).isEqualTo(1);
        assertThat(notifications.countByStatus(NotificationStatus.PENDING)).isZero();
    }

    @Test
    @DisplayName("Một thư hỏng KHÔNG chặn các thư còn lại trong lô")
    void mot_thu_hong_khong_chan_ca_lo() {
        // Ném ra ngoài vòng lặp thì một địa chỉ sai sẽ chặn toàn bộ hàng đợi và mọi khách
        // khác không nhận được gì.
        queue.handle(command(UUID.randomUUID()));
        queue.handle(command(UUID.randomUUID()));
        queue.handle(command(UUID.randomUUID()));

        assertThat(dispatch.runOnce()).isEqualTo(3);
        assertThat(notifications.countByStatus(NotificationStatus.SENT)).isEqualTo(3);
    }

    @Test
    @DisplayName("Máy chủ mail hồi phục thì thư đang chờ được gửi")
    void may_chu_hoi_phuc_thi_gui_duoc() {
        mail.broken = true;
        queue.handle(command(UUID.randomUUID()));
        dispatch.runOnce();

        mail.broken = false;
        jdbc.update("UPDATE notifications SET next_retry_at = now() - interval '1 hour'");

        assertThat(dispatch.runOnce()).isEqualTo(1);
        assertThat(notifications.countByStatus(NotificationStatus.SENT)).isEqualTo(1);
    }

    @Test
    @DisplayName("Thư vé KHÔNG chứa mã QR, chỉ dẫn khách vào ứng dụng")
    void thu_ve_khong_chua_ma_qr() {
        // Mã QR trong email sẽ nằm mãi trong hộp thư và trong mọi bản sao lưu hộp thư đó,
        // trong khi mã trong ứng dụng hết hạn sau vài giờ.
        String body = EmailTemplate.TICKETS_ISSUED.body(java.util.Map.of("headline", "Đêm nhạc ABC"));

        assertThat(body).contains("Vé của tôi").doesNotContain("data:image").doesNotContain("eyJ");
    }

    private static QueueNotificationHandler.Command command(UUID eventId) {
        return new QueueNotificationHandler.Command(
                eventId, "order.paid", "khach@example.com", EmailTemplate.ORDER_PAID, "NT-260908-K7M2QP");
    }
}
