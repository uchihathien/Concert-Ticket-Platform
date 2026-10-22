// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.NOW;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.model.UserStatus;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.StaffInvitationRepository;
import com.nexaticket.identity.domain.port.StaffInvitationRepository.StaffInvitation;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Mời nhân viên soát vé: tài khoản Keycloak tạo sẵn, membership, việc gán sự kiện bên ticketing.
 *
 * <p>Ba hệ thống, không có transaction chung. Mỗi nhánh lỗi ở đây trả lời một câu: hỏng giữa chừng
 * thì còn lại thứ gì, và thứ còn lại đó có chặn được lần mời sau không.
 */
class StaffInvitationHandlerTest {

    private static final Duration LINK = Duration.ofHours(72);
    private static final String EMAIL = "nv.moi@example.com";

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StaffInvitationRepository invitations = mock(StaffInvitationRepository.class);
    private final IdentityProviderPort identityProvider = mock(IdentityProviderPort.class);
    private final CheckinAssignmentPort assignments = mock(CheckinAssignmentPort.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final StaffInvitationHandler handler = new StaffInvitationHandler(
            organizations, users, invitations, identityProvider, assignments, audit, CLOCK, LINK);

    private final UserId admin = newUser();
    private final UserId staff = newUser();
    private final List<UUID> events = List.of(UUID.randomUUID(), UUID.randomUUID());
    private Organization org;

    @BeforeEach
    void setUp() {
        Map<UserId, Role> members = new LinkedHashMap<>();
        members.put(admin, Role.ORG_ADMIN);
        members.put(staff, Role.CHECKIN_STAFF);
        org = organization(ORG, members);
        when(organizations.findById(ORG)).thenReturn(Optional.of(org));
        TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
    }

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private static StaffInvitation invitationOf(UserId user, Instant activatedAt) {
        return new StaffInvitation(
                UUID.randomUUID(),
                ORG,
                user,
                EMAIL,
                "Nhân Viên",
                newUser(),
                NOW,
                NOW,
                NOW.plus(LINK),
                activatedAt,
                null);
    }

    private UserRepository.UserRecord stubNewAccount(String sub) {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(identityProvider.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
        when(identityProvider.createInvitedAccount(EMAIL, "Nhân Viên Mới")).thenReturn(sub);
        UserId created = newUser();
        UserRepository.UserRecord record = new UserRepository.UserRecord(
                created, sub, EMAIL, "Nhân Viên Mới", null, false, UserStatus.ACTIVE, null);
        when(users.upsertByIdpSubject(sub, EMAIL, "Nhân Viên Mới")).thenReturn(record);
        return record;
    }

    @Nested
    @DisplayName("mời người mới")
    class MoiNguoiMoi {

        @Test
        @DisplayName("tạo tài khoản, membership CHECKIN_STAFF, lời mời, gán sự kiện rồi gửi thư — INVITED")
        void moi_thanh_cong() {
            UserRepository.UserRecord created = stubNewAccount("kc-1");

            StaffInvitationHandler.Result result =
                    handler.invite(ORG, "  Nhân Viên Mới ", " NV.Moi@Example.com ", events);

            assertThat(result.outcome()).isEqualTo("INVITED");
            assertThat(result.userId()).isEqualTo(created.id().value());
            assertThat(org.findMember(created.id())).map(Membership::role).contains(Role.CHECKIN_STAFF);

            ArgumentCaptor<StaffInvitation> saved = ArgumentCaptor.forClass(StaffInvitation.class);
            verify(invitations).create(saved.capture());
            assertThat(saved.getValue().invitedBy()).isEqualTo(admin);
            assertThat(saved.getValue().expiresAt()).isEqualTo(NOW.plus(LINK));
            assertThat(saved.getValue().email()).isEqualTo(EMAIL);

            verify(assignments).replace(ORG, created.id(), events, admin);
            verify(identityProvider).sendInvitationEmail("kc-1", LINK);
            verify(invitations).recordDelivery(saved.getValue().id(), NOW, NOW.plus(LINK), null);
            verify(audit).record(eq("STAFF_INVITED"), eq("membership"), any(), isNull(), any());
        }

        @Test
        @DisplayName("thư không đi được thì SEND_FAILED, không rollback tài khoản và việc gán")
        void loi_gui_thu() {
            stubNewAccount("kc-2");
            doThrow(new IdentityProviderPort.IdentityProviderUnavailableException("smtp", null))
                    .when(identityProvider)
                    .sendInvitationEmail(anyString(), any());

            StaffInvitationHandler.Result result = handler.invite(ORG, "Nhân Viên Mới", EMAIL, events);

            assertThat(result.outcome()).isEqualTo("SEND_FAILED");
            verify(invitations).recordDelivery(any(), eq(NOW), eq(NOW.plus(LINK)), eq("Chưa gửi được thư mời"));
            verify(identityProvider, never()).deleteUnactivatedAccount(anyString());
        }

        @Test
        @DisplayName("email đã có tài khoản Keycloak (khách tự đăng ký) thì EMAIL_NOT_INVITABLE, không tạo gì")
        void khach_da_dang_ky() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail(EMAIL))
                    .thenReturn(Optional.of(new IdentityProviderPort.Account("kc-khach", true)));

            assertThatThrownBy(() -> handler.invite(ORG, "Khách", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.EMAIL_NOT_INVITABLE));
            verify(identityProvider, never()).createInvitedAccount(anyString(), anyString());
            verifyNoInteractions(assignments, invitations);
        }

        @Test
        @DisplayName("ticketing không trả lời lúc gán: xoá tài khoản Keycloak vừa tạo và báo 503")
        void ticketing_im_lang() {
            stubNewAccount("kc-3");
            doThrow(new CheckinAssignmentPort.AssignmentsUnavailableException("down", null))
                    .when(assignments)
                    .replace(any(), any(), any(), any());

            assertThatThrownBy(() -> handler.invite(ORG, "Nhân Viên Mới", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITE_UNAVAILABLE));
            verify(identityProvider).deleteUnactivatedAccount("kc-3");
            verify(identityProvider, never()).sendInvitationEmail(anyString(), any());
        }

        @Test
        @DisplayName("dọn tài khoản cũng hỏng thì vẫn báo lỗi gốc, không để lỗi dọn dẹp che mất")
        void don_dep_hong() {
            stubNewAccount("kc-4");
            doThrow(new CheckinAssignmentPort.AssignmentsUnavailableException("down", null))
                    .when(assignments)
                    .replace(any(), any(), any(), any());
            doThrow(new IdentityProviderPort.IdentityProviderUnavailableException("kc down", null))
                    .when(identityProvider)
                    .deleteUnactivatedAccount("kc-4");

            assertThatThrownBy(() -> handler.invite(ORG, "Nhân Viên Mới", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITE_UNAVAILABLE));
        }

        @Test
        @DisplayName("Keycloak không trả lời lúc tạo tài khoản thì 503 với thông điệp chung")
        void keycloak_im_lang() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail(EMAIL))
                    .thenThrow(new IdentityProviderPort.IdentityProviderUnavailableException(
                            "realm nexaticket 500", null));

            assertThatThrownBy(() -> handler.invite(ORG, "A", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITE_UNAVAILABLE))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("realm"));
        }

        @Test
        @DisplayName("tổ chức bị khoá thì không chạm Keycloak")
        void to_chuc_bi_khoa() {
            when(organizations.findById(ORG))
                    .thenReturn(Optional.of(
                            organization(ORG, OrganizationStatus.SUSPENDED, Map.of(admin, Role.ORG_ADMIN))));

            assertThatThrownBy(() -> handler.invite(ORG, "A", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.ORGANIZATION_SUSPENDED));
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("EVENT_MANAGER không mời được nhân viên")
        void quan_ly_su_kien() {
            TenantContextFixture.member(newUser(), ORG, Role.EVENT_MANAGER);

            assertThatThrownBy(() -> handler.invite(ORG, "A", EMAIL, events)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(identityProvider, users);
        }
    }

    @Nested
    @DisplayName("mời một email đã có trong hệ thống")
    class EmailDaCo {

        @Test
        @DisplayName("nhân viên soát vé đang chờ kích hoạt: chỉ gán thêm sự kiện, không gửi thư trùng — PENDING")
        void dang_cho() {
            UUID already = UUID.randomUUID();
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user(staff, EMAIL)));
            when(assignments.eventsOf(ORG, staff)).thenReturn(List.of(already));
            when(invitations.find(ORG, staff)).thenReturn(Optional.of(invitationOf(staff, null)));

            StaffInvitationHandler.Result result = handler.invite(ORG, "A", EMAIL, events);

            assertThat(result.outcome()).isEqualTo("PENDING");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> merged = ArgumentCaptor.forClass(Collection.class);
            verify(assignments).replace(eq(ORG), eq(staff), merged.capture(), eq(admin));
            assertThat(merged.getValue()).containsExactly(already, events.get(0), events.get(1));
            verify(identityProvider, never()).sendInvitationEmail(anyString(), any());
            verify(identityProvider, never()).createInvitedAccount(anyString(), anyString());
        }

        @Test
        @DisplayName("đã kích hoạt và đã gán đủ sự kiện: ALREADY_STAFF, không ghi lại việc gán")
        void da_la_staff() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user(staff, EMAIL)));
            when(assignments.eventsOf(ORG, staff)).thenReturn(events);
            when(invitations.find(ORG, staff)).thenReturn(Optional.of(invitationOf(staff, NOW)));

            assertThat(handler.invite(ORG, "A", EMAIL, events).outcome()).isEqualTo("ALREADY_STAFF");
            verify(assignments, never()).replace(any(), any(), any(), any());
        }

        @Test
        @DisplayName("người đang giữ vai trò khác trong tổ chức: EMAIL_NOT_INVITABLE — không hạ vai trò qua đường mời")
        void vai_tro_khac() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user(admin, EMAIL)));

            assertThatThrownBy(() -> handler.invite(ORG, "A", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.EMAIL_NOT_INVITABLE));
            verifyNoInteractions(assignments);
        }

        @Test
        @DisplayName("người ngoài tổ chức (khách, hay staff tổ chức khác): cùng một lỗi, không lộ lý do")
        void nguoi_ngoai() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user(newUser(), EMAIL)));

            assertThatThrownBy(() -> handler.invite(ORG, "A", EMAIL, events))
                    .is(errorCode(IdentityErrorCode.EMAIL_NOT_INVITABLE))
                    .satisfies(e ->
                            assertThat(e.getMessage()).doesNotContain("khách").doesNotContain("tổ chức khác"));
        }
    }

    @Nested
    @DisplayName("gửi lại và huỷ lời mời")
    class GuiLaiVaHuy {

        @Test
        @DisplayName("gửi lại: xoá tài khoản cũ, tạo tài khoản mới, trỏ users sang sub mới, gửi thư")
        void gui_lai() {
            UserRepository.UserRecord record = user(staff, EMAIL);
            StaffInvitation pending = invitationOf(staff, null);
            when(invitations.find(ORG, staff)).thenReturn(Optional.of(pending));
            when(users.findById(staff)).thenReturn(Optional.of(record));
            when(identityProvider.createInvitedAccount(EMAIL, "Nhân Viên")).thenReturn("kc-moi");

            handler.resend(ORG, staff);

            InOrder order = inOrder(identityProvider, users);
            order.verify(identityProvider).deleteUnactivatedAccount(record.idpSubject());
            order.verify(identityProvider).createInvitedAccount(EMAIL, "Nhân Viên");
            order.verify(users).changeIdpSubject(staff, "kc-moi");
            verify(identityProvider).sendInvitationEmail("kc-moi", LINK);
        }

        @Test
        @DisplayName("gửi lại cho người đã kích hoạt: ghi nhận kích hoạt và báo STAFF_ALREADY_ACTIVATED")
        void gui_lai_da_kich_hoat() {
            UserRepository.UserRecord record = user(staff, EMAIL);
            StaffInvitation pending = invitationOf(staff, null);
            when(invitations.find(ORG, staff)).thenReturn(Optional.of(pending));
            when(users.findById(staff)).thenReturn(Optional.of(record));
            doThrow(new IdentityProviderPort.AccountAlreadyActivatedException(record.idpSubject()))
                    .when(identityProvider)
                    .deleteUnactivatedAccount(record.idpSubject());

            assertThatThrownBy(() -> handler.resend(ORG, staff))
                    .is(errorCode(IdentityErrorCode.STAFF_ALREADY_ACTIVATED));
            verify(invitations).markActivated(pending.id(), NOW);
            verify(identityProvider, never()).createInvitedAccount(anyString(), anyString());
        }

        @Test
        @DisplayName("không có lời mời thì STAFF_INVITATION_NOT_FOUND; đã kích hoạt thì STAFF_ALREADY_ACTIVATED")
        void khong_co_loi_moi() {
            when(invitations.find(ORG, staff)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> handler.resend(ORG, staff))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITATION_NOT_FOUND));

            when(invitations.find(ORG, staff)).thenReturn(Optional.of(invitationOf(staff, NOW)));
            assertThatThrownBy(() -> handler.cancel(ORG, staff))
                    .is(errorCode(IdentityErrorCode.STAFF_ALREADY_ACTIVATED));
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("huỷ: gỡ quyền soát trước, xoá tài khoản sau, rồi xoá membership, lời mời và người dùng")
        void huy() {
            UserRepository.UserRecord record = user(staff, EMAIL);
            StaffInvitation pending = invitationOf(staff, null);
            when(invitations.find(ORG, staff)).thenReturn(Optional.of(pending));
            when(users.findById(staff)).thenReturn(Optional.of(record));

            handler.cancel(ORG, staff);

            InOrder order = inOrder(assignments, identityProvider);
            order.verify(assignments).replace(ORG, staff, List.of(), null);
            order.verify(identityProvider).deleteUnactivatedAccount(record.idpSubject());
            assertThat(org.findMember(staff)).isEmpty();
            verify(organizations).save(org);
            verify(invitations).delete(pending.id());
            verify(users).delete(staff);
        }

        @Test
        @DisplayName("huỷ ở tổ chức bị khoá bị từ chối TRƯỚC khi xoá tài khoản Keycloak")
        void huy_khi_to_chuc_bi_khoa() {
            // cancel() gỡ việc gán và XOÁ tài khoản Keycloak rồi mới gọi activeOrganization(). Tổ chức
            // bị khoá thì lệnh ném ORGANIZATION_SUSPENDED, transaction rollback — membership, lời mời
            // và bản ghi users còn nguyên, nhưng tài khoản Keycloak đã mất và không có gì khôi phục.
            // Bảng Nhân sự vẫn hiện người đó "đang chờ", còn "Gửi lại" sẽ tạo một tài khoản khác.
            when(organizations.findById(ORG))
                    .thenReturn(Optional.of(organization(
                            ORG,
                            OrganizationStatus.SUSPENDED,
                            Map.of(admin, Role.ORG_ADMIN, staff, Role.CHECKIN_STAFF))));
            when(invitations.find(ORG, staff)).thenReturn(Optional.of(invitationOf(staff, null)));
            when(users.findById(staff)).thenReturn(Optional.of(user(staff, EMAIL)));

            assertThatThrownBy(() -> handler.cancel(ORG, staff))
                    .is(errorCode(IdentityErrorCode.ORGANIZATION_SUSPENDED));
            verify(identityProvider, never()).deleteUnactivatedAccount(anyString());
            verify(assignments, never()).replace(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("gán sự kiện")
    class GanSuKien {

        @Test
        @DisplayName("đặt lại danh sách sự kiện của nhân viên soát vé")
        void dat_lai() {
            handler.setEvents(ORG, staff, events);

            verify(assignments).replace(ORG, staff, events, admin);
            verify(audit).record(eq("STAFF_EVENTS_CHANGED"), eq("user"), eq(staff.value()), isNull(), any());
        }

        @Test
        @DisplayName("người quản lý (không phải soát vé) thì NOT_A_MEMBER — họ đã soát được mọi sự kiện")
        void khong_phai_staff() {
            assertThatThrownBy(() -> handler.setEvents(ORG, admin, events))
                    .is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
            assertThatThrownBy(() -> handler.setEvents(ORG, newUser(), events))
                    .is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
            verifyNoInteractions(assignments);
        }

        @Test
        @DisplayName("ticketing không trả lời thì 503, không ghi nhật ký")
        void ticketing_im_lang() {
            doThrow(new CheckinAssignmentPort.AssignmentsUnavailableException("down", null))
                    .when(assignments)
                    .replace(any(), any(), any(), any());

            assertThatThrownBy(() -> handler.setEvents(ORG, staff, events))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITE_UNAVAILABLE));
            verifyNoInteractions(audit);
        }
    }
}
