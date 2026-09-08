// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo tổ chức và gửi lời mời cho chủ sở hữu.
 *
 * <p>ADR-1010: không có luồng tự phục vụ. Superadmin thẩm định ngoài hệ thống rồi mới tạo, nên phần
 * mềm không cần máy trạng thái KYC.
 */
@Service
public class CreateOrganizationHandler {

    public static final String EXCHANGE = "nexaticket.identity";
    private static final int MAX_SLUG_ATTEMPTS = 50;

    private final OrganizationRepository organizations;
    private final InvitationRepository invitations;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final Clock clock;

    public CreateOrganizationHandler(
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

    /** @return token thô của lời mời, chỉ trả về đúng một lần để gửi email */
    @Transactional
    public Result handle(CreateOrganization command) {
        TenantContext.requireSuperAdmin();

        Instant now = clock.instant();
        Slug slug = resolveSlug(command);
        Organization organization = Organization.create(slug, command.name(), now);
        organizations.save(organization);

        Invitation.Issued issued = Invitation.issue(organization.id(), command.ownerEmail(), Role.ORG_OWNER, now);
        invitations.save(issued.invitation());

        audit.record(
                "ORGANIZATION_CREATED",
                "organization",
                organization.id().value(),
                null,
                Map.of("slug", slug.value(), "name", organization.name()));

        outbox.append(
                EXCHANGE,
                "Organization",
                organization.id().value(),
                "organization.created",
                Map.of(
                        "organizationId", organization.id().value().toString(),
                        "slug", slug.value(),
                        "name", organization.name(),
                        "ownerEmail", command.ownerEmail()));

        return new Result(OrganizationView.from(organization), issued.rawToken());
    }

    private Slug resolveSlug(CreateOrganization command) {
        Slug base = (command.slug() == null || command.slug().isBlank())
                ? Slug.from(command.name())
                : new Slug(command.slug());
        if (!organizations.slugExists(base)) {
            return base;
        }
        if (command.slug() != null && !command.slug().isBlank()) {
            // Slug do người dùng chỉ định thì không tự thêm hậu tố — báo lỗi để họ chọn lại.
            throw new ApiException(IdentityErrorCode.SLUG_ALREADY_TAKEN, "Slug already taken: " + base);
        }
        for (int n = 2; n <= MAX_SLUG_ATTEMPTS; n++) {
            Slug candidate = base.withSuffix(n);
            if (!organizations.slugExists(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(IdentityErrorCode.SLUG_ALREADY_TAKEN, "Could not derive a unique slug from name");
    }

    public record Result(OrganizationView organization, String invitationToken) {}
}
