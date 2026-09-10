// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đổi vai trò và gỡ thành viên.
 *
 * <p>Aggregate {@link Organization} đã giữ bất biến "luôn còn ít nhất một {@code ORG_OWNER}" từ
 * lâu — nhưng cho tới giờ không có endpoint nào gọi tới, nên mã lỗi {@code LAST_OWNER} chưa bao giờ
 * được phát ra. Lớp này là đường nối còn thiếu.
 *
 * <p>Bất biến đó không phải chuyện hình thức: mất người sở hữu cuối cùng là tổ chức không còn ai
 * mời được người khác vào, và cách duy nhất để cứu là superadmin can thiệp thủ công vào database.
 *
 * <h3>Ba luật uỷ quyền riêng của việc quản lý thành viên</h3>
 *
 * <ol>
 *   <li>Cần {@code ORG_ADMIN} trở lên (rbac-permission-matrix.md, dòng "Org members invite/role").
 *   <li>Không tự hạ vai trò hay tự gỡ chính mình — nếu không, một quản trị viên bấm nhầm sẽ tự khoá
 *       mình ra ngoài, và đó là loại sự cố không tự sửa được.
 *   <li>Chỉ {@code ORG_OWNER} mới phong được người khác lên {@code ORG_OWNER}. Cho
 *       {@code ORG_ADMIN} làm việc đó là cho họ tự nâng quyền qua trung gian: phong một tài khoản
 *       mình kiểm soát lên OWNER rồi dùng tài khoản đó.
 * </ol>
 */
@Service
public class ManageMembersHandler {

    private final OrganizationRepository organizations;
    private final AuditLogger audit;

    public ManageMembersHandler(OrganizationRepository organizations, AuditLogger audit) {
        this.organizations = organizations;
        this.audit = audit;
    }

    @Transactional
    public void changeRole(TenantId organizationId, UserId memberId, Role newRole) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        if (actor.userId().equals(memberId)) {
            throw ApiException.forbidden("Không tự đổi vai trò của chính mình");
        }
        if (newRole == Role.ORG_OWNER && !actor.superAdmin() && actor.roleIn(organizationId) != Role.ORG_OWNER) {
            throw ApiException.forbidden("Chỉ chủ sở hữu mới phong được chủ sở hữu");
        }

        Organization organization = load(organizationId);
        Role before = organization
                .findMember(memberId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Not a member"))
                .role();

        try {
            organization.changeRole(memberId, newRole);
        } catch (IllegalStateException e) {
            // Aggregate chặn việc hạ vai trò của chủ sở hữu cuối cùng. Dịch sang mã lỗi ổn định để
            // giao diện hiện được câu giải thích, thay vì một lỗi 500 vô nghĩa.
            throw new ApiException(IdentityErrorCode.LAST_OWNER, e.getMessage());
        }
        organizations.save(organization);

        audit.record(
                "MEMBER_ROLE_CHANGED",
                "membership",
                memberId.value(),
                Map.of("role", before.name()),
                Map.of("role", newRole.name()));
    }

    @Transactional
    public void remove(TenantId organizationId, UserId memberId) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        if (actor.userId().equals(memberId)) {
            throw ApiException.forbidden("Không tự gỡ chính mình khỏi tổ chức");
        }

        Organization organization = load(organizationId);
        Role before = organization
                .findMember(memberId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Not a member"))
                .role();

        try {
            organization.removeMember(memberId);
        } catch (IllegalStateException e) {
            throw new ApiException(IdentityErrorCode.LAST_OWNER, e.getMessage());
        }
        organizations.save(organization);

        audit.record("MEMBER_REMOVED", "membership", memberId.value(), Map.of("role", before.name()), null);
    }

    private Organization load(TenantId organizationId) {
        return organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
    }
}
