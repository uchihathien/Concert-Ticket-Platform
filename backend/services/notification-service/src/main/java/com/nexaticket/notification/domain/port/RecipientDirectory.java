// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.port;

import java.util.UUID;

/**
 * Tra địa chỉ nhận thư của một người dùng.
 *
 * <p>Vì sao không nhét email vào sự kiện {@code order.paid}: email là dữ liệu cá nhân, và một khi
 * nó nằm trong message thì nó nằm luôn trong bảng outbox — thứ được giữ <b>vĩnh viễn</b> làm nhật
 * ký sự kiện (ADR-1009). Tra lúc cần thì PII chỉ tồn tại ở identity-service và trong bản ghi thư.
 */
public interface RecipientDirectory {

    /**
     * @throws DirectoryUnavailableException khi không tra được — consumer để message giao lại
     */
    Recipient lookup(UUID userId);

    record Recipient(String email, String displayName) {}

    class DirectoryUnavailableException extends RuntimeException {
        public DirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
