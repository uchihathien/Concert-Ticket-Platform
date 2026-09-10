// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nền tảng gắn thẳng một người vào tổ chức, không qua email mời.
 *
 * <h2>Vì sao có đường này bên cạnh luồng lời mời</h2>
 *
 * <p>Luồng mời hợp lý khi <b>tổ chức tự thêm người của mình</b>: người được mời phải xác nhận, và
 * email là bằng chứng họ đồng ý. Nhưng khi Tổng công ty vừa tạo một tổ chức và cần cấp ngay một
 * {@code ORG_ADMIN} để đơn vị đó bắt đầu làm việc, bắt họ đi qua hộp thư là thêm một chặng có thể
 * hỏng — email vào spam, hộp thư chung không ai đọc — trong đúng lúc mà người vận hành đang ngồi
 * cạnh và có thể xác nhận bằng miệng.
 *
 * <h2>Vì sao vẫn có thể phải rơi về lời mời</h2>
 *
 * <p>Keycloak là nguồn chân lý của danh tính (ADR-0016), và bản ghi người dùng ở đây chỉ ra đời ở
 * <b>request đầu tiên sau khi họ đăng nhập</b>. Nghĩa là với một người chưa từng đăng nhập, ta
 * không có gì để gắn membership vào — không có {@code user_id}, và cũng không được phép bịa ra một
 * bản ghi với {@code idp_subject} giả: khi họ đăng nhập thật, Keycloak cấp một {@code sub} khác,
 * bản ghi thật va vào ràng buộc UNIQUE trên email, và người đó <b>không bao giờ đăng nhập được
 * nữa</b>.
 *
 * <p>Nên lệnh này làm thứ trực tiếp nhất có thể làm, và nói rõ nó đã làm gì:
 *
 * <ul>
 *   <li>Người đã từng đăng nhập ⇒ {@code GRANTED}, có hiệu lực ngay.
 *   <li>Chưa từng ⇒ {@code INVITED}, kèm token lời mời để gửi cho họ.
 * </ul>
 */
@Service
public class GrantMembershipHandler {

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final InviteMemberHandler inviteMember;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final Clock clock;

    public GrantMembershipHandler(
            OrganizationRepository organizations,
            UserRepository users,
            InviteMemberHandler inviteMember,
            OutboxWriter outbox,
            AuditLogger audit,
            Clock clock) {
        this.organizations = organizations;
        this.users = users;
        this.inviteMember = inviteMember;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param outcome {@code GRANTED} nếu đã gắn xong; {@code INVITED} nếu phải gửi lời mời */
    public record Result(String outcome, String email, String role, String userId, String invitationToken) {}

    @Transactional
    public Result handle(TenantId organizationId, String email, Role role) {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_ORG_MANAGE);

        if (role == Role.SUPER_ADMIN || role == Role.CUSTOMER) {
            // Aggregate cũng chặn, nhưng chặn ở đây thì thông báo lỗi nói đúng bằng tiếng nghiệp vụ
            // thay vì bằng một IllegalArgumentException lọt ra thành 500.
            throw new ApiException(
                    com.nexaticket.platform.web.error.ErrorCode.Common.VALIDATION_FAILED,
                    "Vai trò không thuộc phạm vi tổ chức: " + role);
        }

        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        if (!organization.isActive()) {
            throw new ApiException(IdentityErrorCode.ORGANIZATION_SUSPENDED, "Organization is suspended");
        }

        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        Optional<UserRepository.UserRecord> existing = users.findByEmail(normalized);
        if (existing.isEmpty()) {
            // Chưa từng đăng nhập: không có danh tính để gắn vào. Rơi về lời mời — vẫn là một lời
            // gọi, vẫn ra kết quả dùng được, chỉ khác ở chỗ người kia phải bấm một lần.
            String token = inviteMember.handle(organizationId, normalized, role);
            return new Result("INVITED", normalized, role.name(), null, token);
        }

        UserRepository.UserRecord user = existing.get();
        Membership membership;
        try {
            membership = organization.addMember(user.id(), role, clock.instant());
        } catch (IllegalStateException e) {
            throw new ApiException(IdentityErrorCode.ALREADY_A_MEMBER, e.getMessage());
        }
        organizations.save(organization);

        audit.record(
                "MEMBER_GRANTED",
                "membership",
                membership.id(),
                null,
                Map.of("userId", user.id().toString(), "email", normalized, "role", role.name()));

        outbox.append(
                CreateOrganizationHandler.EXCHANGE,
                "Membership",
                membership.id(),
                "member.joined",
                Map.of(
                        "organizationId", organizationId.value().toString(),
                        "userId", user.id().toString(),
                        "role", role.name()));

        return new Result("GRANTED", normalized, role.name(), user.id().toString(), null);
    }
}
