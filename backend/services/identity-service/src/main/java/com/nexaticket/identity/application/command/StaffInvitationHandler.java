// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.StaffInvitationRepository;
import com.nexaticket.identity.domain.port.StaffInvitationRepository.StaffInvitation;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ban tổ chức mời nhân viên soát vé.
 *
 * <h2>Khác luồng lời mời bằng mã ở đâu</h2>
 *
 * <p>Nhân viên soát vé <b>không tự đăng ký</b>. Tài khoản của họ được tạo sẵn ở đây — chưa có mật
 * khẩu, chưa đăng nhập được — và thư mời mang một liên kết dùng một lần để chính họ đặt mật khẩu
 * trên trang của Keycloak. Không ai, kể cả người mời, biết hay đặt được mật khẩu đó.
 *
 * <p>Mỗi người một tài khoản riêng: nhật ký soát vé ghi id người quét, và một tài khoản dùng chung
 * là một nhật ký không nói được ai đã cho ai vào.
 *
 * <h2>Thứ nằm ở đây và thứ không</h2>
 *
 * <p>Tư cách thành viên ({@code CHECKIN_STAFF}) và lời mời nằm ở identity. Việc gán sự kiện nằm ở
 * ticketing-service ({@link CheckinAssignmentPort}); lệnh này chỉ chuyển nó sang đó.
 */
@Service
public class StaffInvitationHandler {

    private static final Logger log = LoggerFactory.getLogger(StaffInvitationHandler.class);

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final StaffInvitationRepository invitations;
    private final IdentityProviderPort identityProvider;
    private final CheckinAssignmentPort assignments;
    private final AuditLogger audit;
    private final Clock clock;
    private final Duration linkLifetime;

    @SuppressWarnings("java:S107") // Sáu cổng + đồng hồ + một cấu hình; gom lại chỉ đổi chỗ đặt.
    public StaffInvitationHandler(
            OrganizationRepository organizations,
            UserRepository users,
            StaffInvitationRepository invitations,
            IdentityProviderPort identityProvider,
            CheckinAssignmentPort assignments,
            AuditLogger audit,
            Clock clock,
            @Value("${nexaticket.identity.staff-invitation.link-lifetime:PT72H}") Duration linkLifetime) {
        this.organizations = organizations;
        this.users = users;
        this.invitations = invitations;
        this.identityProvider = identityProvider;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
        this.linkLifetime = linkLifetime;
    }

    /**
     * @param outcome {@code INVITED} tài khoản mới, đã gửi thư · {@code SEND_FAILED} tài khoản mới
     *     nhưng thư chưa đi (gửi lại được) · {@code PENDING} đã mời từ trước, chưa kích hoạt — chỉ
     *     gán thêm sự kiện, không gửi thư trùng · {@code ALREADY_STAFF} đã là nhân viên soát vé của
     *     tổ chức này — chỉ gán thêm sự kiện
     */
    public record Result(String outcome, UUID userId) {}

    @Transactional
    public Result invite(TenantId organizationId, String fullName, String email, Collection<UUID> eventIds) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        Organization organization = activeOrganization(organizationId);
        String normalized = email.strip().toLowerCase(Locale.ROOT);

        Optional<UserRepository.UserRecord> known = users.findByEmail(normalized);
        if (known.isPresent()) {
            return addEventsToExistingStaff(organization, known.get(), eventIds, actor);
        }
        try {
            if (identityProvider.findAccountByEmail(normalized).isPresent()) {
                // Có tài khoản Keycloak mà chưa từng đăng nhập vào hệ thống: một khách đã tự đăng ký.
                throw notInvitable();
            }
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            throw unavailable(e);
        }

        String idpSubject;
        try {
            idpSubject = identityProvider.createInvitedAccount(normalized, fullName.strip());
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            throw unavailable(e);
        }

        Instant now = clock.instant();
        StaffInvitation invitation;
        UserRepository.UserRecord user;
        try {
            // Biết trước `sub` của người này (chính là id vừa tạo), nên bản ghi người dùng và tư cách
            // thành viên dựng được NGAY — không phải chờ lần đăng nhập đầu như lời mời bằng mã.
            user = users.upsertByIdpSubject(idpSubject, normalized, fullName.strip());
            Membership membership = organization.addMember(user.id(), Role.CHECKIN_STAFF, now);
            organizations.save(organization);
            invitation = new StaffInvitation(
                    UUID.randomUUID(),
                    organizationId,
                    user.id(),
                    normalized,
                    fullName.strip(),
                    actor.userId(),
                    now,
                    now,
                    now.plus(linkLifetime),
                    null,
                    null);
            invitations.create(invitation);
            assignments.replace(organizationId, user.id(), eventIds, actor.userId());
            audit.record(
                    "STAFF_INVITED",
                    "membership",
                    membership.id(),
                    null,
                    Map.of("userId", user.id().toString(), "events", eventIds.size()));
        } catch (RuntimeException e) {
            // Transaction này sẽ rollback, nhưng tài khoản Keycloak thì không tự biến mất. Để nó lại
            // là để lại một email "đã có tài khoản" mà không ai mời lại được.
            discardAccountQuietly(idpSubject);
            throw e instanceof CheckinAssignmentPort.AssignmentsUnavailableException unavailable
                    ? unavailable(unavailable)
                    : e;
        }

        return new Result(
                send(invitation, idpSubject) ? "INVITED" : "SEND_FAILED",
                user.id().value());
    }

    /**
     * Gửi lại lời mời: tài khoản Keycloak cũ bị xoá và tạo lại, nên liên kết trong thư cũ hết dùng.
     *
     * <p>Keycloak không có lệnh thu hồi một liên kết đã gửi; liên kết gắn với id tài khoản, và id
     * cũ biến mất là cách duy nhất. {@code users.id} đứng yên — chỉ {@code idp_subject} đổi — nên
     * lời mời này và việc gán sự kiện bên ticketing vẫn trỏ đúng người.
     */
    @Transactional
    public void resend(TenantId organizationId, UserId userId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        StaffInvitation invitation = pendingInvitation(organizationId, userId);
        UserRepository.UserRecord user = users.findById(userId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User not found"));

        String fresh;
        try {
            identityProvider.deleteUnactivatedAccount(user.idpSubject());
            fresh = identityProvider.createInvitedAccount(invitation.email(), invitation.fullName());
        } catch (IdentityProviderPort.AccountAlreadyActivatedException e) {
            invitations.markActivated(invitation.id(), clock.instant());
            throw alreadyActivated();
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            throw unavailable(e);
        }
        users.changeIdpSubject(userId, fresh);
        audit.record("STAFF_INVITATION_RESENT", "user", userId.value(), null, null);

        send(invitation, fresh);
    }

    /** Huỷ lời mời chưa kích hoạt: xoá tài khoản, tư cách thành viên, và mọi sự kiện đã gán. */
    @Transactional
    public void cancel(TenantId organizationId, UserId userId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        StaffInvitation invitation = pendingInvitation(organizationId, userId);
        UserRepository.UserRecord user = users.findById(userId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // Gỡ quyền trước, xoá tài khoản sau: hỏng giữa chừng thì còn lại một tài khoản không
            // soát được gì, chứ không phải một người vẫn soát được mà không còn ai quản.
            assignments.replace(organizationId, userId, List.of(), null);
            identityProvider.deleteUnactivatedAccount(user.idpSubject());
        } catch (IdentityProviderPort.AccountAlreadyActivatedException e) {
            invitations.markActivated(invitation.id(), clock.instant());
            throw alreadyActivated();
        } catch (IdentityProviderPort.IdentityProviderUnavailableException
                | CheckinAssignmentPort.AssignmentsUnavailableException e) {
            throw unavailable(e);
        }

        Organization organization = activeOrganization(organizationId);
        organization.removeMember(userId);
        organizations.save(organization);
        invitations.delete(invitation.id());
        // Chưa từng đăng nhập nên không có gì khác trỏ vào bản ghi này. Giữ nó lại thì email này bị
        // coi là "đã có tài khoản" và không mời lại được nữa.
        users.delete(userId);
        audit.record("STAFF_INVITATION_CANCELLED", "user", userId.value(), null, null);
    }

    /** Đặt lại những sự kiện một nhân viên được soát. Rỗng là gỡ khỏi mọi sự kiện, giữ tài khoản. */
    @Transactional
    public void setEvents(TenantId organizationId, UserId userId, Collection<UUID> eventIds) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        requireStaff(activeOrganization(organizationId), userId);
        try {
            assignments.replace(organizationId, userId, eventIds, actor.userId());
        } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
            throw unavailable(e);
        }
        audit.record("STAFF_EVENTS_CHANGED", "user", userId.value(), null, Map.of("events", eventIds.size()));
    }

    private Result addEventsToExistingStaff(
            Organization organization, UserRepository.UserRecord user, Collection<UUID> eventIds, TenantScope actor) {
        // Mọi trường hợp "không mời được" trả CÙNG một lỗi: đã là khách, thuộc tổ chức khác, hay
        // đang giữ vai trò khác ở chính tổ chức này. Phân biệt chúng là nói cho người mời biết về
        // một tài khoản không phải của họ.
        Role role = organization.findMember(user.id()).map(Membership::role).orElseThrow(this::notInvitable);
        if (role != Role.CHECKIN_STAFF) {
            throw notInvitable();
        }

        Set<UUID> merged;
        try {
            merged = new LinkedHashSet<>(assignments.eventsOf(organization.id(), user.id()));
            if (merged.addAll(eventIds)) {
                assignments.replace(organization.id(), user.id(), merged, actor.userId());
            }
        } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
            throw unavailable(e);
        }

        boolean pending = invitations
                .find(organization.id(), user.id())
                .map(invitation -> !invitation.isActivated())
                .orElse(false);
        return new Result(pending ? "PENDING" : "ALREADY_STAFF", user.id().value());
    }

    /** @return true nếu thư đã đi; false thì lời mời vẫn còn đó ở trạng thái "lỗi gửi" */
    private boolean send(StaffInvitation invitation, String idpSubject) {
        Instant now = clock.instant();
        try {
            identityProvider.sendInvitationEmail(idpSubject, linkLifetime);
            invitations.recordDelivery(invitation.id(), now, now.plus(linkLifetime), null);
            return true;
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            // Không ném: tài khoản và việc gán đã xong, chỉ thư chưa đi. Ném ở đây là rollback cả
            // lời mời vì một máy chủ thư chập chờn — trong khi bấm "Gửi lại" là đủ.
            //
            // Chi tiết đi vào LOG; bảng Nhân sự chỉ cần biết "chưa gửi được".
            log.warn("Không gửi được thư mời cho {}: {}", invitation.userId(), e.getMessage(), e);
            invitations.recordDelivery(invitation.id(), now, invitation.expiresAt(), "Chưa gửi được thư mời");
            return false;
        }
    }

    private StaffInvitation pendingInvitation(TenantId organizationId, UserId userId) {
        StaffInvitation invitation = invitations
                .find(organizationId, userId)
                .orElseThrow(() -> new ApiException(
                        IdentityErrorCode.STAFF_INVITATION_NOT_FOUND, "Không có lời mời nào cho người này"));
        if (invitation.isActivated()) {
            throw alreadyActivated();
        }
        return invitation;
    }

    private static void requireStaff(Organization organization, UserId userId) {
        Role role = organization
                .findMember(userId)
                .map(Membership::role)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Not a member"));
        if (role != Role.CHECKIN_STAFF) {
            // Người quản lý được sự kiện soát mọi sự kiện của tổ chức; gán riêng cho họ là vô nghĩa.
            throw new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Người này không phải nhân viên soát vé");
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

    private void discardAccountQuietly(String idpSubject) {
        try {
            identityProvider.deleteUnactivatedAccount(idpSubject);
        } catch (RuntimeException cleanup) {
            log.error("Không dọn được tài khoản Keycloak {} sau khi mời hỏng: {}", idpSubject, cleanup.getMessage());
        }
    }

    private ApiException notInvitable() {
        return new ApiException(
                IdentityErrorCode.EMAIL_NOT_INVITABLE,
                "Email này không thể mời làm nhân viên soát vé. Hãy dùng một email khác.");
    }

    private static ApiException alreadyActivated() {
        return new ApiException(
                IdentityErrorCode.STAFF_ALREADY_ACTIVATED,
                "Nhân viên đã kích hoạt tài khoản. Muốn thu quyền, hãy gỡ họ khỏi sự kiện hoặc khỏi tổ chức.");
    }

    private static ApiException unavailable(RuntimeException cause) {
        // Thông điệp gốc mang nguyên văn lỗi của Keycloak / ticketing — realm, đường dẫn nội bộ.
        // Người gọi là quản trị viên tổ chức: họ cần "thử lại sau", còn chi tiết nằm trong log.
        log.warn("Lời mời nhân viên chưa xử lý được: {}", cause.getMessage(), cause);
        return new ApiException(
                IdentityErrorCode.STAFF_INVITE_UNAVAILABLE, "Chưa xử lý được lúc này. Thử lại sau ít phút.");
    }
}
