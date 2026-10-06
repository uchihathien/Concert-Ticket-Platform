// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thu hồi một lời mời chưa dùng.
 *
 * <p>Cần có vì lời mời sống bảy ngày và mang theo quyền: mời nhầm địa chỉ email, hoặc mời đúng
 * người rồi họ nghỉ việc trước khi bấm — không có đường thu hồi thì cách duy nhất là chờ hết hạn.
 *
 * <p>Lời mời đã dùng thì không thu hồi được: khi đó nó không còn là lời mời nữa mà đã thành một
 * membership, và cách gỡ là gỡ thành viên. Gộp hai thứ vào một nút sẽ khiến "thu hồi lời mời" âm
 * thầm đuổi một người đang làm việc.
 */
@Service
public class RevokeInvitationHandler {

    private final InvitationRepository invitations;
    private final AuditLogger audit;

    public RevokeInvitationHandler(InvitationRepository invitations, AuditLogger audit) {
        this.invitations = invitations;
        this.audit = audit;
    }

    @Transactional
    public void handle(TenantId organizationId, UUID invitationId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);

        Invitation invitation = invitations
                .findById(invitationId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.INVITATION_INVALID, "Invitation not found"));

        // Lời mời của tổ chức khác: 404 chứ không phải 403 — 403 xác nhận rằng nó tồn tại.
        if (!invitation.organizationId().equals(organizationId)) {
            throw new ApiException(IdentityErrorCode.INVITATION_INVALID, "Invitation not found");
        }
        if (invitation.acceptedAt() != null) {
            throw new ApiException(
                    IdentityErrorCode.INVITATION_ALREADY_USED,
                    "Lời mời đã được dùng; hãy gỡ thành viên thay vì thu hồi");
        }

        invitations.delete(invitation.id());
        audit.record(
                "INVITATION_REVOKED",
                "invitation",
                invitation.id(),
                Map.of("email", invitation.email(), "role", invitation.role().name()),
                null);
    }
}
