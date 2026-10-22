// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.StaffAccountHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trạng thái tài khoản nhân viên cho màn đăng nhập của app NexaTicket Staff — KHÔNG cần đăng nhập.
 *
 * <p>Keycloak trả cùng một câu "Account disabled" cho tài khoản bị khoá tay lẫn tài khoản hết hạn,
 * mà hai trường hợp đó nhân viên phải làm hai việc khác nhau. Đường này chỉ nói LOCKED / EXPIRED,
 * và gộp mọi thứ còn lại (không có, đang dùng) thành UNKNOWN — xem
 * {@link StaffAccountHandler#publicStatusOf}.
 */
@RestController
@RequestMapping("/v1/staff-accounts")
public class StaffAccountStatusController {

    private final StaffAccountHandler accounts;

    public StaffAccountStatusController(StaffAccountHandler accounts) {
        this.accounts = accounts;
    }

    /** @param status LOCKED · EXPIRED · UNKNOWN */
    public record StatusResponse(String status) {}

    @GetMapping("/status")
    public StatusResponse status(@RequestParam(required = false) String username) {
        return new StatusResponse(accounts.publicStatusOf(username));
    }
}
