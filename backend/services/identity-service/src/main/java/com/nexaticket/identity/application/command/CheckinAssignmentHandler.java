// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.Target;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ban tổ chức gán sự kiện (hoặc từng suất) cho nhân viên soát vé — thường là vài ngày trước giờ
 * diễn.
 *
 * <p>Chỉ gán được người <b>đang là {@code CHECKIN_STAFF}</b> của chính tổ chức này. Luật đó kiểm ở
 * đây vì identity là nơi giữ membership; ticketing chỉ nhận lệnh gán qua đường nội bộ từ service
 * này và tự kiểm phần còn lại — sự kiện có thuộc tổ chức không, suất có thuộc sự kiện không.
 *
 * <p>Chủ tổ chức, quản trị và quản lý sự kiện soát được mọi sự kiện của tổ chức mà không cần gán,
 * nên gán riêng cho họ là vô nghĩa và bị từ chối thay vì âm thầm lưu một dòng không bao giờ được
 * đọc.
 *
 * <p>Phân công tự hết hạn khi suất cuối trong phạm vi được gán kết thúc quá 6 giờ — tính ở
 * ticketing lúc quét, không cần ai khoá tài khoản. Người đó vẫn là nhân viên của tổ chức để lần sau
 * gán tiếp.
 */
@Service
public class CheckinAssignmentHandler {

    /**
     * Một mục cần gán.
     *
     * @param eventSessionId {@code null} là gán cả sự kiện — mọi suất, kể cả suất thêm sau
     */
    public record Item(java.util.UUID eventId, java.util.UUID eventSessionId) {}

    /** Đủ cho mọi sự kiện của một mùa diễn; chặn một request phình to làm nghẽn ticketing. */
    private static final int MAX_TARGETS = 200;

    private final OrganizationRepository organizations;
    private final CheckinAssignmentPort assignments;
    private final AuditLogger audit;

    public CheckinAssignmentHandler(
            OrganizationRepository organizations, CheckinAssignmentPort assignments, AuditLogger audit) {
        this.organizations = organizations;
        this.assignments = assignments;
        this.audit = audit;
    }

    /**
     * Đặt lại các phân công còn hiệu lực của một nhân viên thành đúng {@code targets}.
     *
     * <p>Thay cả tập: màn hình gửi lên trạng thái mong muốn, nên bấm Lưu hai lần cho cùng một kết
     * quả. Phân công đã hết hạn không bị xoá — ticketing giữ chúng làm lịch sử.
     */
    @Transactional
    public void assign(TenantId organizationId, UserId userId, Collection<Item> items) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        List<Target> wanted = List.copyOf(new LinkedHashSet<>(items.stream()
                .map(item -> new Target(item.eventId(), item.eventSessionId()))
                .toList()));
        if (wanted.size() > MAX_TARGETS) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Tối đa " + MAX_TARGETS + " mục mỗi lần gán");
        }
        Organization organization = activeOrganization(organizationId);
        requireStaff(organization, userId);

        try {
            assignments.assign(organizationId, userId, wanted, actor.userId());
        } catch (CheckinAssignmentPort.AssignmentRejectedException e) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, e.getMessage());
        } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
            throw unavailable();
        }
        audit.record("CHECKIN_ASSIGNMENTS_CHANGED", "user", userId.value(), null, Map.of("items", wanted.size()));
    }

    static void requireStaff(Organization organization, UserId userId) {
        Role role = organization
                .findMember(userId)
                .map(Membership::role)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Người này không thuộc tổ chức"));
        if (role != Role.CHECKIN_STAFF) {
            throw new ApiException(
                    IdentityErrorCode.NOT_A_MEMBER,
                    "Chỉ gán sự kiện cho thành viên có vai Staff (soát vé). Người quản lý soát được mọi sự kiện.");
        }
    }

    private Organization activeOrganization(TenantId organizationId) {
        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        if (!organization.isActive()) {
            throw new ApiException(IdentityErrorCode.ORGANIZATION_SUSPENDED, "Organization is suspended");
        }
        return organization;
    }

    private static ApiException unavailable() {
        return new ApiException(
                IdentityErrorCode.CHECKIN_ASSIGNMENTS_UNAVAILABLE, "Chưa lưu được phân công. Thử lại sau ít phút.");
    }
}
