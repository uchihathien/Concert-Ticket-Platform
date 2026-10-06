// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.domain.port.UserRepository;

/**
 * Địa chỉ nhận thư của một người dùng.
 *
 * <p>Tồn tại cho notification-service. Sự kiện {@code order.paid} mang {@code userId} chứ không
 * mang email, và đó là chủ đích: nhét email vào payload là rải dữ liệu cá nhân qua mọi hàng đợi và
 * mọi bản sao lưu của broker. Ai cần thì hỏi đúng lúc gửi.
 *
 * <p>Chỉ có ba trường — không phải cả {@code UserRecord}. Một đường đọc nội bộ trả về nguyên bản
 * ghi là một đường để {@code idpSubject} và cờ {@code superAdmin} rò ra chỗ không cần tới chúng.
 */
public record UserContactView(String userId, String email, String fullName) {

    static UserContactView from(UserRepository.UserRecord user) {
        return new UserContactView(user.id().value().toString(), user.email(), user.fullName());
    }
}
