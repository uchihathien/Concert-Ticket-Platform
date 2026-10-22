// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexaticket.notification.domain.port.EmailSender;
import com.nexaticket.notification.domain.port.RecipientDirectory;
import com.nexaticket.notification.infrastructure.http.IdentityHttpAdapter;
import com.nexaticket.notification.infrastructure.job.NotificationScheduler;
import com.nexaticket.notification.infrastructure.mail.SmtpEmailSender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

/** Các adapter hạ tầng của notification-service: SMTP, identity HTTP và bộ lập lịch. */
class NotificationAdaptersTest {

    @Nested
    @DisplayName("SmtpEmailSender")
    class Smtp {

        private final JavaMailSender mailSender = mock(JavaMailSender.class);
        private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, "no-reply@nexaticket.vn");

        @Test
        @DisplayName("dựng thư thuần chữ với from, to, subject, text đúng")
        void dung_thu() {
            sender.send("khach@example.com", "Đơn NX-1", "Nội dung");

            ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender).send(captor.capture());
            SimpleMailMessage message = captor.getValue();
            assertThat(message.getFrom()).isEqualTo("no-reply@nexaticket.vn");
            assertThat(message.getTo()).containsExactly("khach@example.com");
            assertThat(message.getSubject()).isEqualTo("Đơn NX-1");
            assertThat(message.getText()).isEqualTo("Nội dung");
        }

        @Test
        @DisplayName("mọi lỗi của máy chủ mail bọc thành SendFailedException, giữ nguyên nguyên nhân")
        void boc_loi() {
            MailSendException cause = new MailSendException("550 mailbox unavailable");
            doThrow(cause).when(mailSender).send(any(SimpleMailMessage.class));

            assertThatThrownBy(() -> sender.send("x@y.z", "s", "b"))
                    .isInstanceOf(EmailSender.SendFailedException.class)
                    .hasCause(cause)
                    .hasMessageNotContaining("x@y.z");
        }
    }

    /**
     * Máy chủ HTTP thật trên loopback: adapter tự đặt {@code requestFactory} (timeout) nên
     * {@code MockRestServiceServer} bị ghi đè và không chặn được lời gọi.
     */
    @Nested
    @DisplayName("IdentityHttpAdapter")
    class Identity {

        private static final UUID USER = UUID.fromString("22222222-2222-4222-a222-222222222222");
        private HttpServer server;
        private final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();

        private IdentityHttpAdapter adapterAnswering(int status, String body) throws java.io.IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.start();
            return new IdentityHttpAdapter(
                    RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @AfterEach
        void dung_may_chu() {
            if (server != null) {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("tra thành công: gọi GET /internal/users/{id} và trả email + tên")
        void tra_thanh_cong() throws Exception {
            IdentityHttpAdapter adapter = adapterAnswering(
                    200, "{\"userId\":\"" + USER + "\",\"email\":\"an@example.com\",\"fullName\":\"An\"}");

            assertThat(adapter.lookup(USER)).contains(new RecipientDirectory.Recipient("an@example.com", "An"));
            assertThat(paths).containsExactly("GET /internal/users/" + USER);
        }

        @Test
        @DisplayName("email null hoặc trống: rỗng — không gửi mù")
        void email_trong() throws Exception {
            IdentityHttpAdapter adapter = adapterAnswering(200, "{\"userId\":\"x\",\"email\":\" \"}");

            assertThat(adapter.lookup(USER)).isEmpty();
        }

        @Test
        @DisplayName("404 (người dùng đã xoá): rỗng, không ném — consumer sẽ ack và bỏ qua")
        void khong_tim_thay() throws Exception {
            assertThat(adapterAnswering(404, "{}").lookup(USER)).isEmpty();
        }

        @Test
        @DisplayName("500: rỗng, không ném")
        void loi_may_chu() throws Exception {
            assertThat(adapterAnswering(500, "{}").lookup(USER)).isEmpty();
        }

        @Test
        @DisplayName("không kết nối được: rỗng, không ném")
        void khong_ket_noi_duoc() {
            IdentityHttpAdapter adapter = new IdentityHttpAdapter(RestClient.builder(), "http://127.0.0.1:1");

            assertThat(adapter.lookup(USER)).isEmpty();
        }
    }

    @Test
    @DisplayName("NotificationScheduler: mỗi nhịp gọi đúng một lượt runOnce")
    void scheduler_goi_job() {
        var job = mock(com.nexaticket.notification.application.command.DispatchNotificationsJob.class);

        new NotificationScheduler(job).dispatch();

        verify(job).runOnce();
    }
}
