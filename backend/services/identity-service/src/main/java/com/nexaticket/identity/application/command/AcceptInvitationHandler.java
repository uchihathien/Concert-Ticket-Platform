// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nhận lời mời: tra bằng hash của token, kiểm email khớp, tạo membership. */
@Service
public class AcceptInvitationHandler {

    private final OrganizationRepository organizations;
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final Clock clock;

    public AcceptInvitationHandler(
            OrganizationRepository organizations,
            InvitationRepository invitations,
            UserRepository users,
            OutboxWriter outbox,
            AuditLogger audit,
            Clock clock) {
        this.organizations = organizations;
        this.invitations = invitations;
        this.users = users;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public OrganizationView handle(String rawToken) {
        var currentUser = TenantContext.requireAuthenticated();
        var user = users.findById(currentUser.userId()).orElseThrow(() -> ApiException.notFound("User"));

        Invitation invitation = invitations
                .findByTokenHash(Invitation.hash(rawToken))
                .orElseThrow(() -> new ApiException(IdentityErrorCode.INVITATION_INVALID, "Invitation not found"));

        // Lời mời gửi cho một địa chỉ cụ thể; người khác cầm được token cũng không dùng được.
        if (!invitation.email().equalsIgnoreCase(user.email())) {
            throw new ApiException(IdentityErrorCode.EMAIL_MISMATCH, "Invitation was issued to a different email");
        }

        Instant now = clock.instant();
        try {
            invitation.accept(now);
        } catch (IllegalStateException e) {
            throw new ApiException(
                    invitation.acceptedAt() != null
                            ? IdentityErrorCode.INVITATION_ALREADY_USED
                            : IdentityErrorCode.INVITATION_EXPIRED,
                    e.getMessage());
        }
        invitations.save(invitation);

        Organization organization = organizations
                .findById(invitation.organizationId())
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));

        Membership membership;
        try {
            membership = organization.addMember(user.id(), invitation.role(), now);
        } catch (IllegalStateException e) {
            throw new ApiException(IdentityErrorCode.ALREADY_A_MEMBER, e.getMessage());
        }
        organizations.save(organization);

        audit.record(
                "MEMBER_JOINED",
                "membership",
                membership.id(),
                null,
                Map.of("userId", user.id().toString(), "role", invitation.role().name()));

        outbox.append(
                CreateOrganizationHandler.EXCHANGE,
                "Membership",
                membership.id(),
                "member.joined",
                Map.of(
                        "organizationId", organization.id().value().toString(),
                        "userId", user.id().toString(),
                        "role", invitation.role().name()));

        return OrganizationView.from(organization);
    }
}
