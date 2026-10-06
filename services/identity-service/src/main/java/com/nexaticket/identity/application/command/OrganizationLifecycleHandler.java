// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đổi tên tổ chức, và khoá / mở khoá tổ chức.
 *
 * <p>Hai nhóm thao tác này khác hẳn nhau về người được làm, nên chúng để cạnh nhau ở đây để chỗ
 * khác nhau đó nhìn thấy được:
 *
 * <ul>
 *   <li><b>Đổi tên</b> là việc của tổ chức — {@code ORG_ADMIN} trở lên.
 *   <li><b>Khoá / mở khoá</b> là việc của nền tảng — chỉ superadmin (ADR-1010). Một tổ chức không
 *       thể tự mở khoá cho mình, nếu không thì việc khoá chẳng có ý nghĩa gì.
 * </ul>
 *
 * <p>Khoá tổ chức không xoá gì cả và không đụng tới catalog: sự kiện đang bán vẫn bán, vé đã phát
 * vẫn quét được. Nó chặn đúng những đường đi qua {@code Organization.isActive()} — hiện là mời
 * thành viên. Mở rộng phạm vi chặn là một quyết định riêng, cần cân nhắc từng đường một, chứ không
 * phải hệ quả kèm theo của một nút bấm.
 */
@Service
public class OrganizationLifecycleHandler {

    private final OrganizationRepository organizations;
    private final AuditLogger audit;

    public OrganizationLifecycleHandler(OrganizationRepository organizations, AuditLogger audit) {
        this.organizations = organizations;
        this.audit = audit;
    }

    @Transactional
    public void rename(TenantId organizationId, String newName) {
        TenantContext.requirePermission(Permission.ORG_PROFILE_MANAGE, organizationId);
        Organization organization = load(organizationId);
        String before = organization.name();

        organization.rename(newName);
        organizations.save(organization);

        audit.record(
                "ORGANIZATION_RENAMED",
                "organization",
                organizationId.value(),
                Map.of("name", before),
                Map.of("name", organization.name()));
    }

    @Transactional
    public void suspend(TenantId organizationId) {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_ORG_MANAGE);
        Organization organization = load(organizationId);
        if (!organization.isActive()) {
            throw new ApiException(IdentityErrorCode.ORGANIZATION_SUSPENDED, "Tổ chức đã bị khoá");
        }

        organization.suspend();
        organizations.save(organization);
        audit.record(
                "ORGANIZATION_SUSPENDED",
                "organization",
                organizationId.value(),
                Map.of("status", "ACTIVE"),
                Map.of("status", "SUSPENDED"));
    }

    @Transactional
    public void activate(TenantId organizationId) {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_ORG_MANAGE);
        Organization organization = load(organizationId);

        organization.activate();
        organizations.save(organization);
        audit.record(
                "ORGANIZATION_ACTIVATED",
                "organization",
                organizationId.value(),
                Map.of("status", "SUSPENDED"),
                Map.of("status", "ACTIVE"));
    }

    private Organization load(TenantId organizationId) {
        return organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
    }
}
