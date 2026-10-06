// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.domain.model.Invitation;
import java.time.Instant;

/**
 * Một lời mời đang chờ.
 *
 * <p>KHÔNG chứa token, kể cả dạng hash. Token thô chỉ tồn tại đúng một lần trong phản hồi lúc tạo,
 * để gửi email; hash thì không giúp gì cho giao diện mà lại là thứ đủ để so sánh ngoại tuyến. Lộ
 * nó qua một endpoint danh sách là biến việc đọc danh sách thành việc lấy được quyền vào tổ chức.
 */
public record InvitationView(String id, String email, String role, String expiresAt, boolean expired) {

    public static InvitationView from(Invitation invitation, Instant now) {
        return new InvitationView(
                invitation.id().toString(),
                invitation.email(),
                invitation.role().name(),
                invitation.expiresAt().toString(),
                now.isAfter(invitation.expiresAt()));
    }
}
