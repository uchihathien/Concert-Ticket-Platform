// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.AcceptInvitationHandler;
import com.nexaticket.identity.application.command.InviteMemberHandler;
import com.nexaticket.identity.application.command.ManageMembersHandler;
import com.nexaticket.identity.application.command.OrganizationLifecycleHandler;
import com.nexaticket.identity.application.command.RevokeInvitationHandler;
import com.nexaticket.identity.application.command.RevokeSessionsHandler;
import com.nexaticket.identity.application.command.SendPasswordResetHandler;
import com.nexaticket.identity.application.command.SetPurchaseLimitsHandler;
import com.nexaticket.identity.application.query.AuditQueries;
import com.nexaticket.identity.application.query.InvitationView;
import com.nexaticket.identity.application.query.MemberView;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.PurchaseLimitsView;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khu vực tổ chức.
 *
 * <p>TenantFilter đã kiểm membership từ organizationId trong path và trả 404 nếu người dùng không
 * thuộc tổ chức đó. Nhờ vậy controller không phải kiểm lại việc có thấy được hay không, chỉ kiểm có
 * đủ vai trò hay không.
 *
 * <p>Controller chỉ phụ thuộc tầng application — không chạm aggregate hay repository của domain
 * (ArchitectureRules.hexagonalLayers ép điều này).
 */
@RestController
@RequestMapping("/v1")
@Validated
public class OrganizationController {

    private final OrganizationQueries queries;
    private final InviteMemberHandler inviteMember;
    private final AcceptInvitationHandler acceptInvitation;
    private final ManageMembersHandler manageMembers;
    private final RevokeInvitationHandler revokeInvitation;
    private final OrganizationLifecycleHandler lifecycle;
    private final SetPurchaseLimitsHandler purchaseLimits;
    private final RevokeSessionsHandler revokeSessions;
    private final SendPasswordResetHandler sendPasswordReset;
    private final AuditQueries auditQueries;

    public OrganizationController(
            OrganizationQueries queries,
            InviteMemberHandler inviteMember,
            AcceptInvitationHandler acceptInvitation,
            ManageMembersHandler manageMembers,
            RevokeInvitationHandler revokeInvitation,
            OrganizationLifecycleHandler lifecycle,
            SetPurchaseLimitsHandler purchaseLimits,
            RevokeSessionsHandler revokeSessions,
            SendPasswordResetHandler sendPasswordReset,
            AuditQueries auditQueries) {
        this.queries = queries;
        this.inviteMember = inviteMember;
        this.acceptInvitation = acceptInvitation;
        this.manageMembers = manageMembers;
        this.revokeInvitation = revokeInvitation;
        this.lifecycle = lifecycle;
        this.purchaseLimits = purchaseLimits;
        this.revokeSessions = revokeSessions;
        this.sendPasswordReset = sendPasswordReset;
        this.auditQueries = auditQueries;
    }

    /**
     * Gửi hộ thư đặt lại mật khẩu cho một thành viên.
     *
     * <p>Dành cho lúc nhân viên không tự làm được — không nhận được thư, hoặc gõ sai email lúc đăng
     * ký. Chỉ áp được cho thành viên của chính tổ chức này; người ngoài trả 404.
     *
     * <p>202 chứ không 200: hệ thống đã <b>nhận việc</b> và nhờ Keycloak gửi. Thư tới hộp nào, lúc
     * nào, có bị lọc spam không thì service này không biết và không nên hứa.
     */
    @PostMapping("/organizations/{organizationId}/members/{userId}/password-reset")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendMemberPasswordReset(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        sendPasswordReset.handle(UserId.of(userId), TenantId.of(organizationId));
    }

    /**
     * Buộc một thành viên đăng xuất khỏi mọi thiết bị.
     *
     * <p>Khác với việc gỡ họ khỏi tổ chức: gỡ thành viên đã có hiệu lực ngay từ trước, vì membership
     * được tra lại ở mỗi request. Lệnh này lo phần danh tính — một access token đã phát thì sống hết
     * 15 phút và không có gì khác từ chối được nó.
     *
     * <p>Chỉ áp được cho <b>thành viên của chính tổ chức này</b>; người ngoài trả 404. Không có ràng
     * buộc đó thì {@code ORG_SESSION_REVOKE} trở thành quyền đăng xuất bất kỳ ai trong hệ thống.
     */
    @DeleteMapping("/organizations/{organizationId}/members/{userId}/sessions")
    @RequiresPermission(Permission.ORG_SESSION_REVOKE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeMemberSessions(
            @PathVariable UUID organizationId,
            @PathVariable UUID userId,
            @RequestParam(required = false) @Size(max = 500) String reason) {
        revokeSessions.revokeAll(UserId.of(userId), TenantId.of(organizationId), reason);
    }

    /**
     * Nhật ký kiểm toán của chính tổ chức này.
     *
     * <p>Bảng {@code audit_logs} được ghi từ ngày đầu nhưng chưa từng có đường đọc: mọi thao tác đều
     * để lại vết, và cách duy nhất để xem là mở database bằng tay.
     */
    @GetMapping("/organizations/{organizationId}/audit-logs")
    @RequiresPermission(Permission.ORG_AUDIT_READ)
    public List<AuditQueries.AuditEntry> auditLogs(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return auditQueries.forOrganization(TenantId.of(organizationId), action, limit, offset);
    }

    @GetMapping("/me/organizations")
    public List<OrganizationSummary> myOrganizations() {
        return queries.forCurrentUser().stream().map(OrganizationSummary::from).toList();
    }

    @GetMapping("/organizations/{organizationId}")
    public OrganizationSummary get(@PathVariable UUID organizationId) {
        return OrganizationSummary.from(queries.byId(TenantId.of(organizationId)));
    }

    @GetMapping("/organizations/{organizationId}/members")
    public List<MemberView> members(@PathVariable UUID organizationId) {
        return queries.members(TenantId.of(organizationId));
    }

    @PostMapping("/organizations/{organizationId}/invitations")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public InvitationCreated invite(@PathVariable UUID organizationId, @Valid @RequestBody InviteRequest request) {
        String token = inviteMember.handle(TenantId.of(organizationId), request.email(), request.role());
        return new InvitationCreated(request.email(), request.role().name(), token);
    }

    @PostMapping("/invitations/{token}/accept")
    public OrganizationSummary accept(@PathVariable String token) {
        return OrganizationSummary.from(acceptInvitation.handle(token));
    }

    @PatchMapping("/organizations/{organizationId}")
    @RequiresPermission(Permission.ORG_PROFILE_MANAGE)
    public OrganizationSummary rename(@PathVariable UUID organizationId, @Valid @RequestBody RenameRequest request) {
        lifecycle.rename(TenantId.of(organizationId), request.name());
        return OrganizationSummary.from(queries.byId(TenantId.of(organizationId)));
    }

    @PatchMapping("/organizations/{organizationId}/members/{userId}")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<MemberView> changeRole(
            @PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody RoleRequest request) {
        manageMembers.changeRole(TenantId.of(organizationId), UserId.of(userId), request.role());
        return queries.members(TenantId.of(organizationId));
    }

    @DeleteMapping("/organizations/{organizationId}/members/{userId}")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<MemberView> removeMember(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        manageMembers.remove(TenantId.of(organizationId), UserId.of(userId));
        return queries.members(TenantId.of(organizationId));
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<InvitationView> pendingInvitations(@PathVariable UUID organizationId) {
        return queries.pendingInvitations(TenantId.of(organizationId));
    }

    @DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID organizationId, @PathVariable UUID invitationId) {
        revokeInvitation.handle(TenantId.of(organizationId), invitationId);
    }

    @GetMapping("/organizations/{organizationId}/purchase-limits")
    public PurchaseLimitsView purchaseLimits(@PathVariable UUID organizationId) {
        return purchaseLimits.get(TenantId.of(organizationId));
    }

    /**
     * PUT chứ không PATCH: đây là thay cả bộ trần. Trường nào bỏ trống nghĩa là "kế thừa trần nền
     * tảng" — một giá trị có nghĩa, không phải "không gửi". Với PATCH thì hai nghĩa đó không phân
     * biệt được, và bỏ trống một ô sẽ không bao giờ xoá được giá trị cũ.
     */
    @PutMapping("/organizations/{organizationId}/purchase-limits")
    @RequiresPermission(Permission.ORG_LIMITS_SET)
    public PurchaseLimitsView setPurchaseLimits(
            @PathVariable UUID organizationId, @Valid @RequestBody LimitsRequest request) {
        return purchaseLimits.set(
                TenantId.of(organizationId),
                request.maxSeatedPerHold(),
                request.maxStandingPerHold(),
                request.maxUnitsPerHold(),
                request.maxTicketsPerCustomer());
    }

    public record InviteRequest(@NotBlank @Email String email, @NotNull Role role) {}

    public record RenameRequest(@NotBlank @Size(max = 200) String name) {}

    public record RoleRequest(@NotNull Role role) {}

    /** Null = kế thừa trần nền tảng. Không nhận 0: "mua được 0 vé" không phải một trần hợp lệ. */
    public record LimitsRequest(
            @Positive Integer maxSeatedPerHold,
            @Positive Integer maxStandingPerHold,
            @Positive Integer maxUnitsPerHold,
            @Positive Integer maxTicketsPerCustomer) {}

    /** Token chỉ trả về một lần, để notification-service gửi email. */
    public record InvitationCreated(String email, String role, String token) {}
}
