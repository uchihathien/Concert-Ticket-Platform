// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra thông tin liên hệ của một người dùng — chỉ service nội bộ.
 *
 * <p>Trả <b>đúng hai trường</b> mà notification-service cần: email và tên hiển thị. Không trả số
 * điện thoại, không trả {@code idp_subject}, không trả {@code analytics_subject_id}. Mỗi trường
 * thêm vào đây là một trường PII lan sang một service khác, và đường lan đó rất khó thu lại sau
 * khi đã có consumer dùng.
 */
@Service
public class UserContactQueries {

    private final JdbcTemplate jdbc;

    public UserContactQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Contact(UUID userId, String email, String fullName) {}

    @Transactional(readOnly = true)
    public Contact byId(UUID userId) {
        return jdbc
                .query(
                        "SELECT id, email, full_name FROM users WHERE id = ?",
                        (rs, i) -> new Contact(
                                rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name")),
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(IdentityErrorCode.NOT_A_MEMBER, "User not found"));
    }
}
