// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.port;

/**
 * Gửi thư đi.
 *
 * <p>Là port để đổi nhà cung cấp (SMTP, SES, SendGrid) mà không đụng tới nghiệp vụ xếp hàng và
 * retry — và để test chạy được mà không cần máy chủ mail.
 */
public interface EmailSender {

    void send(String recipient, String subject, String body);

    /** Gửi hỏng vì lý do tạm thời; thư sẽ được thử lại. */
    class SendFailedException extends RuntimeException {
        public SendFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
