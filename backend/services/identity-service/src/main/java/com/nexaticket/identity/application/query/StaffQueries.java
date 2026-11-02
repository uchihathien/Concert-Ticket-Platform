// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bảng phân công soát vé của ban tổ chức, và danh sách sự kiện mà chính người gọi được soát.
 *
 * <p>Mỗi dòng là một thành viên có vai Staff. Các cột lời mời ({@code status}, {@code invitedAt}…)
 * chỉ còn ý nghĩa với nhân viên được mời theo luồng cũ — luồng đó đã ngừng, xem
 * {@code StaffInvitationHandler}. Thành viên thêm theo cách mới luôn {@code ACTIVE}.
 */
@Service
public class StaffQueries {

    private static final Logger log = LoggerFactory.getLogger(StaffQueries.class);

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final StaffInvitationRepository invitations;
    private final IdentityProviderPort identityProvider;
    private final CheckinAssignmentPort assignments;
    private final Clock clock;

    public StaffQueries(
            OrganizationRepository organizations,
            UserRepository users,
            StaffInvitationRepository invitations,
            IdentityProviderPort identityProvider,
            CheckinAssignmentPort assignments,
            Clock clock) {
        this.organizations = organizations;
        this.users = users;
        this.invitations = invitations;
        this.identityProvider = identityProvider;
        this.assignments = assignments;
        this.clock = clock;
    }

    /**
     * @param status {@code INVITED} đã gửi thư, chờ đặt mật khẩu · {@code ACTIVE} đã kích hoạt ·
     *     {@code EXPIRED} liên kết hết hạn · {@code SEND_FAILED} thư chưa đi
     * @param invitedAt {@code null} với nhân viên có từ trước khi có mục Nhân sự
     * @param invitedBy tên người mời, để hiển thị
     * @param eventIds sự kiện của các phân công CÒN HIỆU LỰC — giữ cho client cũ
     * @param assignments mọi phân công, kể cả đã hết hạn, kèm trạng thái
     */
    public record StaffRow(
            String userId,
            String email,
            String fullName,
            String status,
            String invitedAt,
            String invitedBy,
            String expiresAt,
            String activatedAt,
            List<UUID> eventIds,
            List<AssignmentRow> assignments) {}

    /**
     * @param eventSessionId {@code null} khi gán cả sự kiện
     * @param status {@code UPCOMING} sắp tới · {@code ONGOING} đang diễn ra · {@code EXPIRED} đã hết
     *     hạn · {@code UNKNOWN} chưa đọc được lịch diễn
     */
    public record AssignmentRow(
            UUID eventId, UUID eventSessionId, String eventTitle, String startsAt, String expiresAt, String status) {

        static AssignmentRow from(CheckinAssignmentPort.Assignment assignment) {
            return new AssignmentRow(
                    assignment.eventId(),
                    assignment.eventSessionId(),
                    assignment.eventTitle(),
                    assignment.startsAt() == null ? null : assignment.startsAt().toString(),
                    assignment.expiresAt() == null
                            ? null
                            : assignment.expiresAt().toString(),
                    assignment.status());
        }
    }

    /**
     * @param restricted false nghĩa là người gọi quản lý được sự kiện của tổ chức nên soát mọi sự
     *     kiện của tổ chức; khi đó {@code eventIds} rỗng và không mang nghĩa gì
     */
    public record MyEvents(boolean restricted, List<UUID> eventIds) {}

    /** Chỉ nhân viên soát vé. Chủ tổ chức, quản trị, quản lý sự kiện tự soát được và không nằm ở đây. */
    @Transactional
    public List<StaffRow> staffOf(TenantId organizationId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));

        List<UserId> staffIds = organization.members().stream()
                .filter(member -> member.role() == Role.CHECKIN_STAFF)
                .map(Membership::userId)
                .toList();
        Map<UserId, UserRepository.UserRecord> byId = users.findAllByIds(staffIds).stream()
                .collect(Collectors.toMap(UserRepository.UserRecord::id, Function.identity()));
        Map<UserId, StaffInvitation> invited = invitations.findByOrganization(organizationId).stream()
                .collect(Collectors.toMap(StaffInvitation::userId, Function.identity()));

        Map<UserId, UserRepository.UserRecord> inviters = users
                .findAllByIds(invited.values().stream()
                        .map(StaffInvitation::invitedBy)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(UserRepository.UserRecord::id, Function.identity()));

        Map<UserId, List<CheckinAssignmentPort.Assignment>> events;
        try {
            events = assignments.byOrganization(organizationId);
        } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
            // Không trả một bảng thiếu cột sự kiện: người xem sẽ tưởng chưa ai được gán gì, bấm Lưu,
            // và ghi đè một danh sách rỗng lên danh sách thật.
            log.warn("Không đọc được việc gán sự kiện: {}", e.getMessage(), e);
            throw new ApiException(
                    IdentityErrorCode.CHECKIN_ASSIGNMENTS_UNAVAILABLE,
                    "Chưa tải được danh sách phân công. Thử lại sau ít phút.");
        }

        Instant now = clock.instant();
        return staffIds.stream()
                .filter(byId::containsKey)
                .map(id -> {
                    UserRepository.UserRecord user = byId.get(id);
                    StaffInvitation invitation = refreshActivation(invited.get(id), user, now);
                    UserRepository.UserRecord inviter = invitation == null || invitation.invitedBy() == null
                            ? null
                            : inviters.get(invitation.invitedBy());
                    List<CheckinAssignmentPort.Assignment> mine = events.getOrDefault(id, List.of());
                    return new StaffRow(
                            id.value().toString(),
                            user.email(),
                            user.fullName(),
                            statusOf(invitation, now),
                            invitation == null ? null : invitation.invitedAt().toString(),
                            inviter == null
                                    ? null
                                    : (inviter.fullName() != null ? inviter.fullName() : inviter.email()),
                            invitation == null ? null : invitation.expiresAt().toString(),
                            invitation == null || invitation.activatedAt() == null
                                    ? null
                                    : invitation.activatedAt().toString(),
                            mine.stream()
                                    .filter(CheckinAssignmentPort.Assignment::active)
                                    .map(CheckinAssignmentPort.Assignment::eventId)
                                    .distinct()
                                    .toList(),
                            mine.stream().map(AssignmentRow::from).toList());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public MyEvents myEvents(TenantId organizationId) {
        TenantScope scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, organizationId);
        if (TenantContext.has(Permission.CATALOG_MANAGE, organizationId)) {
            return new MyEvents(false, List.of());
        }
        try {
            return new MyEvents(true, assignments.eventsOf(organizationId, scope.userId()));
        } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
            log.warn("Không đọc được sự kiện được gán: {}", e.getMessage(), e);
            throw new ApiException(
                    IdentityErrorCode.CHECKIN_ASSIGNMENTS_UNAVAILABLE,
                    "Chưa tải được sự kiện được gán. Thử lại sau ít phút.");
        }
    }

    /**
     * Hỏi Keycloak xem một lời mời đang chờ đã được kích hoạt chưa, và ghi lại nếu rồi.
     *
     * <p>Việc đặt mật khẩu diễn ra bên Keycloak, không đi qua service này, nên không có sự kiện
     * nào báo về. Hỏi lúc đọc bảng là đủ: chỉ những dòng còn chờ mới bị hỏi, và một khi đã ghi
     * {@code activated_at} thì không hỏi lại nữa.
     */
    private StaffInvitation refreshActivation(StaffInvitation invitation, UserRepository.UserRecord user, Instant now) {
        if (invitation == null || invitation.isActivated()) {
            return invitation;
        }
        try {
            boolean activated = identityProvider
                    .findAccount(user.idpSubject())
                    .map(IdentityProviderPort.Account::activated)
                    .orElse(false);
            if (!activated) {
                return invitation;
            }
            invitations.markActivated(invitation.id(), now);
            return new StaffInvitation(
                    invitation.id(),
                    invitation.organizationId(),
                    invitation.userId(),
                    invitation.email(),
                    invitation.fullName(),
                    invitation.invitedBy(),
                    invitation.invitedAt(),
                    invitation.lastSentAt(),
                    invitation.expiresAt(),
                    now,
                    null);
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            // Keycloak im lặng thì giữ trạng thái đang biết; bảng vẫn hiện, chỉ có thể chậm một nhịp.
            return invitation;
        }
    }

    private static String statusOf(StaffInvitation invitation, Instant now) {
        if (invitation == null || invitation.isActivated()) {
            return "ACTIVE";
        }
        if (invitation.deliveryError() != null) {
            return "SEND_FAILED";
        }
        return now.isAfter(invitation.expiresAt()) ? "EXPIRED" : "INVITED";
    }
}
