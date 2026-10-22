// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.NOW;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.OTHER_ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static com.nexaticket.identity.application.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Hai đầu của lời mời bằng mã: người nhận chấp nhận, quản trị viên thu hồi.
 *
 * <p>Lời mời mang theo quyền và sống bảy ngày, nên mỗi nhánh từ chối ở đây là một cách cầm token
 * của người khác mà vẫn không vào được tổ chức.
 */
class InvitationLifecycleHandlersTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final AcceptInvitationHandler accept =
            new AcceptInvitationHandler(organizations, invitations, users, outbox, audit, CLOCK);
    private final RevokeInvitationHandler revoke = new RevokeInvitationHandler(invitations, audit);

    private final UserId owner = newUser();
    private final UserId invitee = newUser();

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private static Invitation pending(TenantId org, String email, Role role) {
        return Invitation.rehydrate(
                UUID.randomUUID(), org, email, role, "hash-" + UUID.randomUUID(), NOW.plusSeconds(3600), null);
    }

    private Organization stubOrg() {
        Map<UserId, Role> members = new HashMap<>();
        members.put(owner, Role.ORG_OWNER);
        Organization org = organization(ORG, members);
        when(organizations.findById(ORG)).thenReturn(Optional.of(org));
        return org;
    }

    @Nested
    @DisplayName("chấp nhận lời mời")
    class ChapNhan {

        private void signedInAs(String email) {
            TenantContextFixture.customer(invitee);
            when(users.findById(invitee)).thenReturn(Optional.of(user(invitee, email)));
        }

        @Test
        @DisplayName("đúng email: thành thành viên, lời mời bị đánh dấu đã dùng, phát member.joined")
        void dung_email() {
            Organization org = stubOrg();
            Invitation invitation = pending(ORG, "moi@example.com", Role.EVENT_MANAGER);
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            signedInAs("Moi@Example.com");

            OrganizationView view = accept.handle(invitation.id());

            assertThat(org.findMember(invitee)).map(Membership::role).contains(Role.EVENT_MANAGER);
            assertThat(invitation.acceptedAt()).isEqualTo(NOW);
            verify(invitations).save(invitation);
            verify(organizations).save(org);
            verify(outbox)
                    .append(
                            eq(CreateOrganizationHandler.EXCHANGE),
                            eq("Membership"),
                            any(),
                            eq("member.joined"),
                            any());
            verify(audit).record(eq("MEMBER_JOINED"), eq("membership"), any(), isNull(), any());
            assertThat(view.id()).isEqualTo(ORG.toString());
        }

        @Test
        @DisplayName("tra bằng token thô: hash của token là khoá tra cứu, token thô không đi xuống database")
        void tra_bang_token() {
            stubOrg();
            Invitation.Issued issued = Invitation.issue(ORG, "moi@example.com", Role.ORG_ADMIN, NOW);
            when(invitations.findByTokenHash(Invitation.hash(issued.rawToken())))
                    .thenReturn(Optional.of(issued.invitation()));
            signedInAs("moi@example.com");

            accept.handle(issued.rawToken());

            verify(invitations, never()).findByTokenHash(issued.rawToken());
            assertThat(issued.invitation().acceptedAt()).isNotNull();
        }

        @Test
        @DisplayName("token không khớp lời mời nào thì INVITATION_INVALID")
        void token_sai() {
            signedInAs("moi@example.com");
            when(invitations.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accept.handle("khong-ton-tai"))
                    .is(errorCode(IdentityErrorCode.INVITATION_INVALID));
        }

        @Test
        @DisplayName("người khác cầm được token cũng không dùng được: EMAIL_MISMATCH, lời mời còn nguyên")
        void email_khac() {
            stubOrg();
            Invitation invitation = pending(ORG, "moi@example.com", Role.ORG_ADMIN);
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            signedInAs("ke-khac@example.com");

            assertThatThrownBy(() -> accept.handle(invitation.id())).is(errorCode(IdentityErrorCode.EMAIL_MISMATCH));
            assertThat(invitation.acceptedAt()).isNull();
            verify(invitations, never()).save(any());
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("lời mời hết hạn thì INVITATION_EXPIRED (410)")
        void het_han() {
            Invitation invitation = Invitation.rehydrate(
                    UUID.randomUUID(), ORG, "moi@example.com", Role.ORG_ADMIN, "h", NOW.minusSeconds(1), null);
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            signedInAs("moi@example.com");

            assertThatThrownBy(() -> accept.handle(invitation.id()))
                    .is(errorCode(IdentityErrorCode.INVITATION_EXPIRED));
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("lời mời đã dùng thì INVITATION_ALREADY_USED (409), không phải hết hạn")
        void da_dung() {
            Invitation invitation = Invitation.rehydrate(
                    UUID.randomUUID(),
                    ORG,
                    "moi@example.com",
                    Role.ORG_ADMIN,
                    "h",
                    NOW.plusSeconds(60),
                    NOW.minusSeconds(60));
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            signedInAs("moi@example.com");

            assertThatThrownBy(() -> accept.handle(invitation.id()))
                    .is(errorCode(IdentityErrorCode.INVITATION_ALREADY_USED));
        }

        @Test
        @DisplayName("lời mời soát vé phát từ trước khi có mục Nhân sự không còn dùng được")
        void loi_moi_staff_cu() {
            Invitation invitation = pending(ORG, "moi@example.com", Role.CHECKIN_STAFF);
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            signedInAs("moi@example.com");

            assertThatThrownBy(() -> accept.handle(invitation.id()))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITE_REQUIRED));
            assertThat(invitation.acceptedAt()).isNull();
        }

        @Test
        @DisplayName("đã là thành viên thì ALREADY_A_MEMBER, không tạo membership thứ hai")
        void da_la_thanh_vien() {
            Organization org = stubOrg();
            org.addMember(invitee, Role.EVENT_MANAGER, NOW);
            Invitation invitation = pending(ORG, "moi@example.com", Role.ORG_ADMIN);
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            signedInAs("moi@example.com");

            assertThatThrownBy(() -> accept.handle(invitation.id())).is(errorCode(IdentityErrorCode.ALREADY_A_MEMBER));
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("chưa đăng nhập thì 401 trước khi tra lời mời")
        void chua_dang_nhap() {
            assertThatThrownBy(() -> accept.handle(UUID.randomUUID())).is(errorCode(ErrorCode.Common.UNAUTHENTICATED));
            verifyNoInteractions(invitations);
        }
    }

    @Nested
    @DisplayName("thu hồi lời mời")
    class ThuHoi {

        @Test
        @DisplayName("ORG_ADMIN thu hồi lời mời chưa dùng: xoá hẳn dòng và ghi nhật ký")
        void thu_hoi() {
            Invitation invitation = pending(ORG, "moi@example.com", Role.EVENT_MANAGER);
            when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
            TenantContextFixture.member(owner, ORG, Role.ORG_ADMIN);

            revoke.handle(ORG, invitation.id());

            verify(invitations).delete(invitation.id());
            verify(audit)
                    .record(
                            "INVITATION_REVOKED",
                            "invitation",
                            invitation.id(),
                            Map.of("email", "moi@example.com", "role", "EVENT_MANAGER"),
                            null);
        }

        @Test
        @DisplayName("lời mời của tổ chức khác trả INVITATION_INVALID như không tồn tại, không xoá gì")
        void loi_moi_cua_to_chuc_khac() {
            Invitation foreign = pending(OTHER_ORG, "moi@example.com", Role.ORG_ADMIN);
            when(invitations.findById(foreign.id())).thenReturn(Optional.of(foreign));
            TenantContextFixture.member(owner, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> revoke.handle(ORG, foreign.id()))
                    .is(errorCode(IdentityErrorCode.INVITATION_INVALID));
            verify(invitations, never()).delete(any());
        }

        @Test
        @DisplayName("lời mời đã dùng không thu hồi được — phải gỡ thành viên")
        void da_dung() {
            Invitation used = Invitation.rehydrate(
                    UUID.randomUUID(), ORG, "a@example.com", Role.ORG_ADMIN, "h", NOW.plusSeconds(60), NOW);
            when(invitations.findById(used.id())).thenReturn(Optional.of(used));
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> revoke.handle(ORG, used.id()))
                    .is(errorCode(IdentityErrorCode.INVITATION_ALREADY_USED));
            verify(invitations, never()).delete(any());
        }

        @Test
        @DisplayName("EVENT_MANAGER không thu hồi được lời mời")
        void quan_ly_su_kien() {
            TenantContextFixture.member(owner, ORG, Role.EVENT_MANAGER);

            assertThatThrownBy(() -> revoke.handle(ORG, UUID.randomUUID())).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(invitations);
        }

        @Test
        @DisplayName("lời mời không tồn tại thì INVITATION_INVALID")
        void khong_ton_tai() {
            when(invitations.findById(any())).thenReturn(Optional.empty());
            TenantContextFixture.member(owner, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> revoke.handle(ORG, UUID.randomUUID()))
                    .is(errorCode(IdentityErrorCode.INVITATION_INVALID));
        }
    }
}
