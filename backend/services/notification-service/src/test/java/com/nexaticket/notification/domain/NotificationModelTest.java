// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.model.Notification;
import com.nexaticket.notification.domain.model.NotificationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Mô hình miền của thư: vòng đời gửi/thử lại ({@link Notification}) và nội dung mẫu
 * ({@link EmailTemplate}).
 */
class NotificationModelTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

    private static Notification pending(EmailTemplate template, String headline) {
        return Notification.queue(
                UUID.randomUUID(), "order.paid", "khach@example.com", template, Map.of("headline", headline));
    }

    @Nested
    @DisplayName("Vòng đời gửi thư")
    class Lifecycle {

        @Test
        @DisplayName("thư mới xếp hàng: PENDING, chưa thử lần nào")
        void thu_moi() {
            Notification n = pending(EmailTemplate.ORDER_PAID, "NX-1");

            assertThat(n.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.attempts()).isZero();
            assertThat(n.nextRetryAt()).isNull();
            assertThat(n.sentAt()).isNull();
            assertThat(n.id()).isNotNull();
        }

        @Test
        @DisplayName("markSent: SENT kèm thời điểm gửi")
        void mark_sent() {
            Notification n = pending(EmailTemplate.ORDER_PAID, "NX-1");

            n.markSent(NOW);

            assertThat(n.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.sentAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("backoff mũ: lần hỏng thứ 1,2,3,4 thử lại sau 1,2,4,8 phút và vẫn PENDING")
        void backoff_mu() {
            Notification n = pending(EmailTemplate.ORDER_PAID, "NX-1");
            long[] expectedMinutes = {1, 2, 4, 8};

            for (int i = 0; i < expectedMinutes.length; i++) {
                n.markFailed("smtp timeout", NOW);

                assertThat(n.attempts()).isEqualTo(i + 1);
                assertThat(n.status()).isEqualTo(NotificationStatus.PENDING);
                assertThat(n.nextRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(expectedMinutes[i])));
            }
        }

        @Test
        @DisplayName("lần hỏng thứ 5: DEAD, không còn lịch thử lại — phải có người nhìn")
        void het_luot_thi_chet() {
            Notification n = pending(EmailTemplate.TICKETS_ISSUED, "Đêm nhạc");

            for (int i = 0; i < 5; i++) {
                n.markFailed("boom", NOW);
            }

            assertThat(n.status()).isEqualTo(NotificationStatus.DEAD);
            assertThat(n.attempts()).isEqualTo(5);
            assertThat(n.nextRetryAt()).isNull();
        }

        @Test
        @DisplayName("lỗi dài bị cắt còn 500 ký tự; lỗi null được chấp nhận")
        void cat_loi_dai() {
            Notification n = pending(EmailTemplate.ORDER_PAID, "NX-1");

            n.markFailed("x".repeat(2000), NOW);
            assertThat(n.lastError()).hasSize(500);

            n.markFailed(null, NOW);
            assertThat(n.lastError()).isNull();
        }

        @Test
        @DisplayName("rehydrate giữ nguyên trạng thái và số lần đã thử từ database")
        void rehydrate() {
            UUID id = UUID.randomUUID();
            UUID event = UUID.randomUUID();

            Notification n = Notification.rehydrate(
                    id,
                    event,
                    "order.paid",
                    "a@b.c",
                    EmailTemplate.ORDER_PAID,
                    Map.of("headline", "NX"),
                    NotificationStatus.PENDING,
                    3);

            assertThat(n.id()).isEqualTo(id);
            assertThat(n.eventId()).isEqualTo(event);
            assertThat(n.attempts()).isEqualTo(3);
            // Lần hỏng kế tiếp là lần thứ 4: thử lại sau 8 phút.
            n.markFailed("e", NOW);
            assertThat(n.nextRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(8)));
        }

        @Test
        @DisplayName("payload được sao chép phòng thủ: sửa map gốc không đổi nội dung thư")
        void payload_sao_chep_phong_thu() {
            Map<String, Object> source = new HashMap<>();
            source.put("headline", "NX-1");
            Notification n =
                    Notification.queue(UUID.randomUUID(), "order.paid", "a@b.c", EmailTemplate.ORDER_PAID, source);

            source.put("headline", "NX-HACKED");

            assertThat(n.subject()).contains("NX-1").doesNotContain("HACKED");
        }
    }

    @Nested
    @DisplayName("Mẫu thư")
    class Templates {

        @ParameterizedTest(name = "{0}")
        @EnumSource(EmailTemplate.class)
        @DisplayName("mọi mẫu: tiêu đề và thân đều nhắc tên (headline) và không bỏ trống")
        void moi_mau_nhac_headline(EmailTemplate template) {
            Map<String, Object> payload = Map.of("headline", "TÊN-DUY-NHẤT");

            assertThat(template.subject(payload)).contains("TÊN-DUY-NHẤT");
            assertThat(template.body(payload)).contains("TÊN-DUY-NHẤT").isNotBlank();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(EmailTemplate.class)
        @DisplayName("không mẫu nào chứa mã QR hay token — thư chỉ dẫn khách mở ứng dụng")
        void khong_chua_qr_hay_token(EmailTemplate template) {
            String text =
                    (template.subject(Map.of("headline", "x")) + template.body(Map.of("headline", "x"))).toLowerCase();

            assertThat(text).doesNotContain("qr=").doesNotContain("token").doesNotContain("http");
        }

        @Test
        @DisplayName("tiêu đề đúng mẫu tiếng Việt cho từng loại")
        void tieu_de_dung_mau() {
            assertThat(EmailTemplate.ORDER_PAID.subject(Map.of("headline", "NX-9")))
                    .isEqualTo("Đơn hàng NX-9 đã thanh toán thành công");
            assertThat(EmailTemplate.ORDER_EXPIRED.subject(Map.of("headline", "NX-9")))
                    .isEqualTo("Đơn hàng NX-9 đã hết hạn thanh toán");
            assertThat(EmailTemplate.ORGANIZATION_INVITATION.subject(Map.of("headline", "Acme")))
                    .isEqualTo("Lời mời tham gia Acme trên NexaTicket");
            assertThat(EmailTemplate.TICKETS_ISSUED.subject(Map.of("headline", "Đêm nhạc")))
                    .isEqualTo("Vé của bạn cho Đêm nhạc đã sẵn sàng");
            assertThat(EmailTemplate.TICKET_CHECKED_IN.subject(Map.of("headline", "Sân A")))
                    .isEqualTo("Vé của bạn đã check-in tại Sân A");
        }

        @Test
        @DisplayName("thiếu headline: dùng chuỗi rỗng, không NPE")
        void thieu_headline() {
            assertThat(EmailTemplate.ORDER_PAID.subject(Map.of())).isEqualTo("Đơn hàng  đã thanh toán thành công");
            assertThat(EmailTemplate.ORDER_PAID.body(Map.of())).isNotBlank();
        }

        @Test
        @DisplayName("headline chứa ký tự định dạng %s %n không làm vỡ mẫu")
        void headline_chua_ky_tu_dinh_dang() {
            assertThat(EmailTemplate.ORDER_PAID.subject(Map.of("headline", "100%s %d")))
                    .isEqualTo("Đơn hàng 100%s %d đã thanh toán thành công");
            assertThat(EmailTemplate.ORDER_PAID.body(Map.of("headline", "100%s %d")))
                    .contains("100%s %d");
        }

        /**
         * Tên sự kiện/tổ chức đi thẳng vào tiêu đề thư. Một tên có CR/LF cho phép chèn thêm header
         * (Bcc:, Reply-To:) vào thư — nên tiêu đề phải một dòng. Cố ý để đỏ nếu mẫu chưa lọc.
         */
        @Test
        @DisplayName("headline có CR/LF không được tạo ra tiêu đề nhiều dòng (chống chèn header SMTP)")
        void headline_khong_chen_header() {
            String subject = EmailTemplate.ORDER_PAID.subject(Map.of("headline", "NX-1\r\nBcc: ke-xau@example.com"));

            assertThat(subject).doesNotContain("\r").doesNotContain("\n");
        }
    }
}
