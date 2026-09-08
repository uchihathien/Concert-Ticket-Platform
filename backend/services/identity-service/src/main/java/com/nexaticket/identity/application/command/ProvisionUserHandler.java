// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.domain.port.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo bản ghi người dùng ở lần request đầu tiên sau khi đăng nhập OIDC (auth-oidc.md bước 3).
 *
 * <p>Idempotent: gọi lại nhiều lần cho cùng một {@code idpSubject} chỉ cập nhật email và tên.
 *
 * <p>Tồn tại như một use case riêng thay vì để controller gọi thẳng repository — biên HTTP không
 * được chạm vào domain (ArchitectureRules.hexagonalLayers).
 */
@Service
public class ProvisionUserHandler {

    private final UserRepository users;

    public ProvisionUserHandler(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public void handle(String idpSubject, String email, String fullName) {
        users.upsertByIdpSubject(idpSubject, email, fullName);
    }
}
