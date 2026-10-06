// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mời thành viên, gồm cả nhân viên soát vé.
 *
 * <p>Đây là quyền của {@code ORG_ADMIN}+, không cần superadmin — tổ chức tự chủ việc thêm người.
 */
@Service
public class InviteMemberHandler {

    private final OrganizationRepository organizations;
    private final InvitationRepository invitations;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final Clock clock;

    public InviteMemberHandler(
            OrganizationRepository organizations,
            InvitationRepository invitations,
            OutboxWriter outbox,
            AuditLogger audit,
            Clock clock) {
        this.organizations = organizations;
        this.invitations = invitations;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public String handle(TenantId organizationId, String email, Role role) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);

        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        if (!organization.isActive()) {
            throw new ApiException(IdentityErrorCode.ORGANIZATION_SUSPENDED, "Organization is suspended");
        }

        Invitation.Issued issued = Invitation.issue(organizationId, email, role, clock.instant());
        invitations.save(issued.invitation());

        audit.record(
                "MEMBER_INVITED",
                "invitation",
                issued.invitation().id(),
                null,
                Map.of("email", email, "role", role.name()));

        outbox.append(
                CreateOrganizationHandler.EXCHANGE,
                "Invitation",
                issued.invitation().id(),
                "member.invited",
                Map.of(
                        "organizationId", organizationId.value().toString(),
                        // Tên tổ chức đi kèm sự kiện, không để consumer gọi ngược lại hỏi.
                        // Thư mời có tiêu đề "Lời mời tham gia <tên>", và một consumer phải gọi
                        // ngược về Identity chỉ để lấy một chuỗi là một consumer chết theo Identity.
                        "organizationName", organization.name(),
                        "email", email,
                        "role", role.name(),
                        "expiresAt", issued.invitation().expiresAt().toString()));

        return issued.rawToken();
    }
}
