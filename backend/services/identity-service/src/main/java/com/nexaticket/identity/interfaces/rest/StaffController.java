// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.StaffAccountHandler;
import com.nexaticket.identity.application.command.StaffInvitationHandler;
import com.nexaticket.identity.application.query.StaffQueries;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mục Nhân sự: ban tổ chức cấp tài khoản (hoặc mời) nhân viên soát vé và gán họ vào sự kiện.
 *
 * <p>Hai cách có nhân viên: {@code POST /accounts} cấp tài khoản tên đăng nhập + PIN (mặc định trên
 * web), và {@code POST /} mời bằng email (luồng cũ, giữ để tương thích).
 *
 * <p>Gỡ hẳn một nhân viên khỏi tổ chức vẫn là {@code DELETE /organizations/{id}/members/{userId}};
 * ở đây chỉ có những việc riêng của nhân viên soát vé.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/staff")
public class StaffController {

    private final StaffInvitationHandler invitations;
    private final StaffAccountHandler accounts;
    private final StaffQueries queries;

    public StaffController(StaffInvitationHandler invitations, StaffAccountHandler accounts, StaffQueries queries) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.queries = queries;
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Size(max = 20) @Pattern(regexp = "^$|^[0-9+ .()-]{6,20}$") String phone,
            @NotNull @Size(max = 200) List<UUID> eventIds) {}

    /**
     * Thông tin in lên phiếu nhân viên — {@code pin} CHỈ có ở đây, đúng một lần.
     *
     * @param loginUri nội dung mã QR đăng nhập trên phiếu
     */
    public record AccountCredentials(
            String userId, String fullName, String username, String pin, List<UUID> eventIds, String loginUri) {

        static AccountCredentials of(StaffAccountHandler.Credentials credentials) {
            return new AccountCredentials(
                    credentials.userId().toString(),
                    credentials.fullName(),
                    credentials.username(),
                    credentials.pin(),
                    credentials.eventIds(),
                    "nexaticket-staff://login?u=" + credentials.username() + "&p=" + credentials.pin());
        }
    }

    /** @param until bỏ trống là gia hạn thêm 24 giờ kể từ bây giờ */
    public record ExtendRequest(Instant until) {}

    public record ExtendResponse(Instant accessUntil) {}

    public record InviteRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(max = 200) List<UUID> eventIds) {}

    public record InviteResponse(String outcome, String userId) {}

    public record EventsRequest(@NotNull @Size(max = 200) List<UUID> eventIds) {}

    @GetMapping
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<StaffQueries.StaffRow> list(@PathVariable UUID organizationId) {
        return queries.staffOf(TenantId.of(organizationId));
    }

    @PostMapping
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public InviteResponse invite(@PathVariable UUID organizationId, @Valid @RequestBody InviteRequest request) {
        var result = invitations.invite(
                TenantId.of(organizationId), request.fullName(), request.email(), request.eventIds());
        return new InviteResponse(result.outcome(), result.userId().toString());
    }

    /** Cấp tài khoản nhân viên: tên đăng nhập tự sinh + PIN 6 số. */
    @PostMapping("/accounts")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public AccountCredentials createAccount(
            @PathVariable UUID organizationId, @Valid @RequestBody CreateAccountRequest request) {
        return AccountCredentials.of(
                accounts.create(TenantId.of(organizationId), request.fullName(), request.phone(), request.eventIds()));
    }

    /** Cấp PIN mới (phiếu mới) và đóng mọi phiên đang mở của nhân viên. */
    @PostMapping("/{userId}/pin")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public AccountCredentials resetPin(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        return AccountCredentials.of(accounts.resetPin(TenantId.of(organizationId), UserId.of(userId)));
    }

    @PostMapping("/{userId}/lock")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lock(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        accounts.lock(TenantId.of(organizationId), UserId.of(userId));
    }

    @PostMapping("/{userId}/unlock")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        accounts.unlock(TenantId.of(organizationId), UserId.of(userId));
    }

    @PostMapping("/{userId}/extend")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public ExtendResponse extend(
            @PathVariable UUID organizationId,
            @PathVariable UUID userId,
            @RequestBody(required = false) ExtendRequest request) {
        return new ExtendResponse(accounts.extend(
                TenantId.of(organizationId), UserId.of(userId), request == null ? null : request.until()));
    }

    /** Sự kiện mà CHÍNH người gọi được soát — app soát vé dùng để chỉ hiện đúng những suất đó. */
    @GetMapping("/me/events")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public StaffQueries.MyEvents myEvents(@PathVariable UUID organizationId) {
        return queries.myEvents(TenantId.of(organizationId));
    }

    @PutMapping("/{userId}/events")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<StaffQueries.StaffRow> setEvents(
            @PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody EventsRequest request) {
        invitations.setEvents(TenantId.of(organizationId), UserId.of(userId), request.eventIds());
        return queries.staffOf(TenantId.of(organizationId));
    }

    @PostMapping("/{userId}/invitation/resend")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resend(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        invitations.resend(TenantId.of(organizationId), UserId.of(userId));
    }

    @DeleteMapping("/{userId}/invitation")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        invitations.cancel(TenantId.of(organizationId), UserId.of(userId));
    }
}
