// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final Clock clock;

    public OrganizationQueries(
            OrganizationRepository organizations, InvitationRepository invitations, UserRepository users, Clock clock) {
        this.organizations = organizations;
        this.invitations = invitations;
        this.users = users;
        this.clock = clock;
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

    /**
     * Bảng thành viên, kèm email và tên.
     *
     * <p>Một truy vấn cho cả danh sách chứ không phải mỗi dòng một truy vấn: bảng của một tổ chức
     * hai mươi người sẽ là hai mươi vòng khứ hồi nếu tra trong vòng lặp.
     */
    public List<MemberView> members(TenantId organizationId) {
        List<MemberView> rows = byId(organizationId).members();
        List<UserId> ids = rows.stream()
                .map(row -> UserId.of(java.util.UUID.fromString(row.userId())))
                .toList();
        Map<String, UserRepository.UserRecord> byId = users.findAllByIds(ids).stream()
                .collect(Collectors.toMap(user -> user.id().toString(), Function.identity()));
        return rows.stream().map(row -> row.withUser(byId.get(row.userId()))).toList();
    }

    /**
     * Lời mời chưa dùng.
     *
     * <p>Đòi {@code ORG_ADMIN} trở lên, không phải chỉ là thành viên: danh sách này để lộ ai sắp
     * được vào tổ chức và với vai trò nào — thông tin của người quản lý, không phải của mọi nhân
     * viên soát vé.
     */
    public List<InvitationView> pendingInvitations(TenantId organizationId) {
        requireOrgAdmin(organizationId);
        return invitations.findPending(organizationId).stream()
                .map(invitation -> InvitationView.from(invitation, clock.instant()))
                .toList();
    }

    /** Hồ sơ của người đang đăng nhập, kèm tổ chức và vai trò. */
    public ProfileView currentProfile() {
        TenantScope scope = TenantContext.requireAuthenticated();
        UserRepository.UserRecord user = users.findById(scope.userId())
                .orElseThrow(() -> new ApiException(IdentityErrorCode.NOT_A_MEMBER, "User not found"));

        List<ProfileView.Membership> memberships = organizations.findAllByMember(scope.userId()).stream()
                .map(organization -> {
                    Role role = organization
                            .findMember(scope.userId())
                            .map(com.nexaticket.identity.domain.model.Membership::role)
                            .orElse(null);
                    return new ProfileView.Membership(
                            organization.id().toString(),
                            organization.slug().value(),
                            organization.name(),
                            organization.status().name(),
                            role == null ? null : role.name());
                })
                .toList();

        return new ProfileView(
                user.id().toString(), user.email(), user.fullName(), user.phone(), user.superAdmin(), memberships);
    }

    /** Danh sách toàn hệ thống — chỉ superadmin (ADR-1010). */
    public List<OrganizationView> all(int limit, int offset) {
        TenantContext.requireSuperAdmin();
        return organizations.findAll(Math.min(limit, MAX_PAGE_SIZE), Math.max(offset, 0)).stream()
                .map(OrganizationView::from)
                .toList();
    }

    private void requireOrgAdmin(TenantId organizationId) {
        TenantScope scope = TenantContext.requireAuthenticated();
        if (scope.superAdmin()) {
            return;
        }
        Role role = scope.roleIn(organizationId);
        if (role == null || !role.isAtLeastOrgAdmin()) {
            throw ApiException.forbidden("Requires ORG_ADMIN or above");
        }
    }
}
