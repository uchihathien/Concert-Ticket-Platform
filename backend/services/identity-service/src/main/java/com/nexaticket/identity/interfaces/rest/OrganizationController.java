// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.AcceptInvitationHandler;
import com.nexaticket.identity.application.command.InviteMemberHandler;
import com.nexaticket.identity.application.command.ManageMembersHandler;
import com.nexaticket.identity.application.command.OrganizationLifecycleHandler;
import com.nexaticket.identity.application.command.RevokeInvitationHandler;
import com.nexaticket.identity.application.command.SetPurchaseLimitsHandler;
import com.nexaticket.identity.application.query.InvitationView;
import com.nexaticket.identity.application.query.MemberView;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.PurchaseLimitsView;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khu vực tổ chức.
 *
 * <p>TenantFilter đã kiểm membership từ organizationId trong path và trả 404 nếu người dùng không
 * thuộc tổ chức đó. Nhờ vậy controller không phải kiểm lại việc có thấy được hay không, chỉ kiểm có
 * đủ vai trò hay không.
 *
 * <p>Controller chỉ phụ thuộc tầng application — không chạm aggregate hay repository của domain
 * (ArchitectureRules.hexagonalLayers ép điều này).
 */
@RestController
@RequestMapping("/v1")
public class OrganizationController {

    private final OrganizationQueries queries;
    private final InviteMemberHandler inviteMember;
    private final AcceptInvitationHandler acceptInvitation;
    private final ManageMembersHandler manageMembers;
    private final RevokeInvitationHandler revokeInvitation;
    private final OrganizationLifecycleHandler lifecycle;
    private final SetPurchaseLimitsHandler purchaseLimits;

    public OrganizationController(
            OrganizationQueries queries,
            InviteMemberHandler inviteMember,
            AcceptInvitationHandler acceptInvitation,
            ManageMembersHandler manageMembers,
            RevokeInvitationHandler revokeInvitation,
            OrganizationLifecycleHandler lifecycle,
            SetPurchaseLimitsHandler purchaseLimits) {
        this.queries = queries;
        this.inviteMember = inviteMember;
        this.acceptInvitation = acceptInvitation;
        this.manageMembers = manageMembers;
        this.revokeInvitation = revokeInvitation;
        this.lifecycle = lifecycle;
        this.purchaseLimits = purchaseLimits;
    }

    @GetMapping("/me/organizations")
    public List<OrganizationSummary> myOrganizations() {
        return queries.forCurrentUser().stream().map(OrganizationSummary::from).toList();
    }

    @GetMapping("/organizations/{organizationId}")
    public OrganizationSummary get(@PathVariable UUID organizationId) {
        return OrganizationSummary.from(queries.byId(TenantId.of(organizationId)));
    }

    @GetMapping("/organizations/{organizationId}/members")
    public List<MemberView> members(@PathVariable UUID organizationId) {
        return queries.members(TenantId.of(organizationId));
    }

    @PostMapping("/organizations/{organizationId}/invitations")
    public InvitationCreated invite(@PathVariable UUID organizationId, @Valid @RequestBody InviteRequest request) {
        String token = inviteMember.handle(TenantId.of(organizationId), request.email(), request.role());
        return new InvitationCreated(request.email(), request.role().name(), token);
    }

    @PostMapping("/invitations/{token}/accept")
    public OrganizationSummary accept(@PathVariable String token) {
        return OrganizationSummary.from(acceptInvitation.handle(token));
    }

    @PatchMapping("/organizations/{organizationId}")
    public OrganizationSummary rename(@PathVariable UUID organizationId, @Valid @RequestBody RenameRequest request) {
        lifecycle.rename(TenantId.of(organizationId), request.name());
        return OrganizationSummary.from(queries.byId(TenantId.of(organizationId)));
    }

    @PatchMapping("/organizations/{organizationId}/members/{userId}")
    public List<MemberView> changeRole(
            @PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody RoleRequest request) {
        manageMembers.changeRole(TenantId.of(organizationId), UserId.of(userId), request.role());
        return queries.members(TenantId.of(organizationId));
    }

    @DeleteMapping("/organizations/{organizationId}/members/{userId}")
    public List<MemberView> removeMember(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        manageMembers.remove(TenantId.of(organizationId), UserId.of(userId));
        return queries.members(TenantId.of(organizationId));
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    public List<InvitationView> pendingInvitations(@PathVariable UUID organizationId) {
        return queries.pendingInvitations(TenantId.of(organizationId));
    }

    @DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID organizationId, @PathVariable UUID invitationId) {
        revokeInvitation.handle(TenantId.of(organizationId), invitationId);
    }

    @GetMapping("/organizations/{organizationId}/purchase-limits")
    public PurchaseLimitsView purchaseLimits(@PathVariable UUID organizationId) {
        return purchaseLimits.get(TenantId.of(organizationId));
    }

    /**
     * PUT chứ không PATCH: đây là thay cả bộ trần. Trường nào bỏ trống nghĩa là "kế thừa trần nền
     * tảng" — một giá trị có nghĩa, không phải "không gửi". Với PATCH thì hai nghĩa đó không phân
     * biệt được, và bỏ trống một ô sẽ không bao giờ xoá được giá trị cũ.
     */
    @PutMapping("/organizations/{organizationId}/purchase-limits")
    public PurchaseLimitsView setPurchaseLimits(
            @PathVariable UUID organizationId, @Valid @RequestBody LimitsRequest request) {
        return purchaseLimits.set(
                TenantId.of(organizationId),
                request.maxSeatedPerHold(),
                request.maxStandingPerHold(),
                request.maxUnitsPerHold(),
                request.maxTicketsPerCustomer());
    }

    public record InviteRequest(@NotBlank @Email String email, @NotNull Role role) {}

    public record RenameRequest(@NotBlank @Size(max = 200) String name) {}

    public record RoleRequest(@NotNull Role role) {}

    /** Null = kế thừa trần nền tảng. Không nhận 0: "mua được 0 vé" không phải một trần hợp lệ. */
    public record LimitsRequest(
            @Positive Integer maxSeatedPerHold,
            @Positive Integer maxStandingPerHold,
            @Positive Integer maxUnitsPerHold,
            @Positive Integer maxTicketsPerCustomer) {}

    /** Token chỉ trả về một lần, để notification-service gửi email. */
    public record InvitationCreated(String email, String role, String token) {}
}
