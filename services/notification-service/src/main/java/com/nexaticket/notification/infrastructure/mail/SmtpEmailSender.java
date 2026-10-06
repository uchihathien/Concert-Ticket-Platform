// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.mail;

import com.nexaticket.notification.domain.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Gửi qua SMTP. Ở dev là Mailpit, ở production là nhà cung cấp thật.
 *
 * <p>Mọi lỗi được bọc thành {@link SendFailedException} để tầng application xử lý đồng nhất: nó
 * không cần biết lỗi đến từ mạng, từ xác thực hay từ địa chỉ sai — cả ba đều dẫn tới "thử lại rồi
 * bỏ cuộc sau năm lần".
 */
@Component
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(
            JavaMailSender mailSender, @Value("${nexaticket.notification.from:no-reply@nexaticket.vn}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            // KHÔNG log địa chỉ email: log của hệ thống không phải chỗ chứa dữ liệu cá nhân.
            log.debug("Đã gửi thư: {}", subject);
        } catch (RuntimeException e) {
            throw new SendFailedException("Không gửi được thư", e);
        }
    }
}
