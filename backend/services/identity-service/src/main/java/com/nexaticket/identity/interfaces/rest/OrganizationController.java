// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.AcceptInvitationHandler;
import com.nexaticket.identity.application.command.InviteMemberHandler;
import com.nexaticket.identity.application.query.MemberView;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public OrganizationController(
            OrganizationQueries queries, InviteMemberHandler inviteMember, AcceptInvitationHandler acceptInvitation) {
        this.queries = queries;
        this.inviteMember = inviteMember;
        this.acceptInvitation = acceptInvitation;
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

    public record InviteRequest(@NotBlank @Email String email, @NotNull Role role) {}

    /** Token chỉ trả về một lần, để notification-service gửi email. */
    public record InvitationCreated(String email, String role, String token) {}
}
