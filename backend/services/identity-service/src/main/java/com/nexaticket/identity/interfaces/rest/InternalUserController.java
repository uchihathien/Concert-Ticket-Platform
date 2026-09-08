// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.query.UserContactQueries;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service — chỉ service nội bộ gọi được.
 *
 * <p>Gateway không route {@code /internal/**} ra ngoài. Ở đây bắt buộc: endpoint này trả email của
 * một người dùng bất kỳ theo id, nên để lọt ra internet là một kênh dò dữ liệu cá nhân hàng loạt.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final UserContactQueries contacts;

    public InternalUserController(UserContactQueries contacts) {
        this.contacts = contacts;
    }

    @GetMapping("/{userId}/contact")
    public UserContactQueries.Contact contact(@PathVariable UUID userId) {
        return contacts.byId(userId);
    }
}
