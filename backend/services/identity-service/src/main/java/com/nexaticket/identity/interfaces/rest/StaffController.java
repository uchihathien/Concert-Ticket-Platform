// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

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
import jakarta.validation.constraints.Size;
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
 * Mục Nhân sự: ban tổ chức mời nhân viên soát vé và gán họ vào sự kiện.
 *
 * <p>Gỡ hẳn một nhân viên khỏi tổ chức vẫn là {@code DELETE /organizations/{id}/members/{userId}};
 * ở đây chỉ có những việc riêng của nhân viên soát vé.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/staff")
public class StaffController {

    private final StaffInvitationHandler invitations;
    private final StaffQueries queries;

    public StaffController(StaffInvitationHandler invitations, StaffQueries queries) {
        this.invitations = invitations;
        this.queries = queries;
    }

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
