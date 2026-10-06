// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Tra địa chỉ nhận thư từ id người dùng.
 *
 * <p>Tồn tại vì sự kiện của các service khác mang {@code userId} chứ <b>không</b> mang email — và
 * đó là chủ đích: nhét email vào payload là rải dữ liệu cá nhân qua mọi hàng đợi, mọi DLQ và mọi
 * bản sao lưu của broker. Service này hỏi đúng một lần, đúng lúc sắp gửi.
 */
public interface RecipientDirectory {

    /** @return rỗng nếu không tra được — người gọi phải bỏ qua thư đó, không được gửi mù */
    Optional<Recipient> lookup(UUID userId);

    record Recipient(String email, String fullName) {}
}
