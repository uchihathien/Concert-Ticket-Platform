// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đường đọc.
 *
 * <p>Ở identity, khối lượng dữ liệu nhỏ nên đọc qua repository của aggregate là đủ. Với các đường
 * đọc nặng (ví dụ {@code GET /sessions/{id}/seats} trả vài nghìn chỗ), query side đọc thẳng bằng
 * {@code JdbcTemplate} và bỏ qua domain hoàn toàn — CQRS ở mức nhẹ (tactical-ddd.md §7).
 */
@Service
@Transactional(readOnly = true)
public class OrganizationQueries {

    private static final int MAX_PAGE_SIZE = 200;

    private final OrganizationRepository organizations;

    public OrganizationQueries(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    public OrganizationView byId(TenantId id) {
        return organizations
                .findById(id)
                .map(OrganizationView::from)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
    }

    /** Các tổ chức mà người dùng hiện tại là thành viên. Hạt nhân của tenant isolation. */
    public List<OrganizationView> forCurrentUser() {
        var scope = TenantContext.requireAuthenticated();
        return organizations.findAllByMember(scope.userId()).stream()
                .map(OrganizationView::from)
                .toList();
    }

    public List<MemberView> members(TenantId organizationId) {
        return byId(organizationId).members();
    }

    /** Danh sách toàn hệ thống — chỉ superadmin (ADR-1010). */
    public List<OrganizationView> all(int limit, int offset) {
        TenantContext.requireSuperAdmin();
        return organizations.findAll(Math.min(limit, MAX_PAGE_SIZE), Math.max(offset, 0)).stream()
                .map(OrganizationView::from)
                .toList();
    }
}
