// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.NOW;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.disabledUser;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static com.nexaticket.identity.application.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Ba lệnh chỉ nền tảng làm được: cấp thẳng thành viên, khoá/mở tài khoản, ghi nhật ký hộ service khác.
 */
class PlatformHandlersTest {

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    @Nested
    @DisplayName("cấp thẳng thành viên")
    class CapThanhVien {

        private final OrganizationRepository organizations = mock(OrganizationRepository.class);
        private final UserRepository users = mock(UserRepository.class);
        private final InvitationRepository invitations = mock(InvitationRepository.class);
        private final OutboxWriter outbox = mock(OutboxWriter.class);
        private final AuditLogger audit = mock(AuditLogger.class);
        private final InviteMemberHandler invite =
                new InviteMemberHandler(organizations, invitations, outbox, audit, CLOCK);
        private final GrantMembershipHandler handler =
                new GrantMembershipHandler(organizations, users, invite, outbox, audit, CLOCK);

        private Organization stubOrg(OrganizationStatus status) {
            Map<UserId, Role> members = new HashMap<>();
            members.put(newUser(), Role.ORG_OWNER);
            Organization org = organization(ORG, status, members);
            when(organizations.findById(ORG)).thenReturn(Optional.of(org));
            return org;
        }

        @Test
        @DisplayName("người đã từng đăng nhập: GRANTED, có hiệu lực ngay, email được chuẩn hoá trước khi tra")
        void da_dang_nhap() {
            Organization org = stubOrg(OrganizationStatus.ACTIVE);
            UserId existing = newUser();
            when(users.findByEmail("quan.tri@example.com"))
                    .thenReturn(Optional.of(user(existing, "quan.tri@example.com")));
            TenantContextFixture.superAdmin(newUser());

            GrantMembershipHandler.Result result = handler.handle(ORG, "  Quan.Tri@Example.COM ", Role.ORG_ADMIN);

            assertThat(result.outcome()).isEqualTo("GRANTED");
            assertThat(result.invitationToken()).isNull();
            assertThat(result.userId()).isEqualTo(existing.toString());
            assertThat(org.findMember(existing)).map(Membership::role).contains(Role.ORG_ADMIN);
            verify(organizations).save(org);
            verify(outbox).append(anyString(), eq("Membership"), any(), eq("member.joined"), any());
            verify(invitations, never()).save(any());
        }

        @Test
        @DisplayName("người chưa từng đăng nhập: rơi về lời mời, không bịa bản ghi người dùng")
        void chua_dang_nhap_thi_moi() {
            stubOrg(OrganizationStatus.ACTIVE);
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            TenantContextFixture.superAdmin(newUser());

            GrantMembershipHandler.Result result = handler.handle(ORG, "moi@example.com", Role.EVENT_MANAGER);

            assertThat(result.outcome()).isEqualTo("INVITED");
            assertThat(result.invitationToken()).isNotBlank();
            assertThat(result.userId()).isNull();
            verify(invitations).save(any());
            verify(users, never()).upsertByIdpSubject(anyString(), anyString(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"ORG_OWNER", "ORG_ADMIN"})
        @DisplayName("vai trò tổ chức, kể cả chủ sở hữu, không dùng được đường của nền tảng")
        void to_chuc_khong_dung_duoc(Role role) {
            TenantContextFixture.member(newUser(), ORG, role);

            assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.ORG_ADMIN))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations, users);
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"SUPER_ADMIN", "CUSTOMER"})
        @DisplayName("vai trò ngoài phạm vi tổ chức là VALIDATION_FAILED")
        void vai_tro_ngoai_pham_vi(Role role) {
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", role))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            verifyNoInteractions(organizations);
        }

        @Test
        @DisplayName("vai trò soát vé đi qua mục Nhân sự, kể cả với superadmin")
        void staff_phai_qua_nhan_su() {
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.CHECKIN_STAFF))
                    .is(errorCode(IdentityErrorCode.STAFF_INVITE_REQUIRED));
        }

        @Test
        @DisplayName("tổ chức bị khoá thì không cấp được")
        void to_chuc_bi_khoa() {
            stubOrg(OrganizationStatus.SUSPENDED);
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.ORG_ADMIN))
                    .is(errorCode(IdentityErrorCode.ORGANIZATION_SUSPENDED));
        }

        @Test
        @DisplayName("đã là thành viên thì ALREADY_A_MEMBER")
        void da_la_thanh_vien() {
            Organization org = stubOrg(OrganizationStatus.ACTIVE);
            UserId existing = newUser();
            org.addMember(existing, Role.EVENT_MANAGER, NOW);
            when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user(existing, "a@example.com")));
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.handle(ORG, "a@example.com", Role.ORG_ADMIN))
                    .is(errorCode(IdentityErrorCode.ALREADY_A_MEMBER));
            verify(organizations, never()).save(any());
        }
    }

    @Nested
    @DisplayName("khoá và mở tài khoản")
    class TaiKhoan {

        private final UserRepository users = mock(UserRepository.class);
        private final AuditLogger audit = mock(AuditLogger.class);
        private final UserLifecycleHandler handler = new UserLifecycleHandler(users, audit, CLOCK);

        @Test
        @DisplayName("vô hiệu hoá thì đặt DISABLED và thu hồi mọi token phát trước lúc này")
        void vo_hieu_hoa() {
            UserId target = newUser();
            when(users.findById(target)).thenReturn(Optional.of(user(target, "a@example.com")));
            TenantContextFixture.superAdmin(newUser());

            handler.disable(target, "Nghỉ việc");

            verify(users).setStatus(target, UserStatus.DISABLED);
            verify(users).revokeTokensIssuedBefore(target, NOW);
            verify(audit)
                    .record(
                            "USER_DISABLED",
                            "user",
                            target.value(),
                            Map.of("status", "ACTIVE"),
                            Map.of("status", "DISABLED", "reason", "Nghỉ việc"));
        }

        @Test
        @DisplayName("lý do null vẫn ghi được nhật ký — Map.of không nhận null")
        void ly_do_null() {
            UserId target = newUser();
            when(users.findById(target)).thenReturn(Optional.of(user(target, "a@example.com")));
            TenantContextFixture.superAdmin(newUser());

            handler.disable(target, null);

            verify(users).setStatus(target, UserStatus.DISABLED);
        }

        @Test
        @DisplayName("vô hiệu hoá hai lần là no-op, không ghi nhật ký thừa")
        void idempotent() {
            UserId target = newUser();
            when(users.findById(target)).thenReturn(Optional.of(disabledUser(target, "a@example.com")));
            TenantContextFixture.superAdmin(newUser());

            handler.disable(target, "x");

            verify(users, never()).setStatus(any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("superadmin không tự khoá được mình")
        void khong_tu_khoa() {
            UserId me = newUser();
            when(users.findById(me)).thenReturn(Optional.of(user(me, "me@example.com")));
            TenantContextFixture.superAdmin(me);

            assertThatThrownBy(() -> handler.disable(me, "x")).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verify(users, never()).setStatus(any(), any());
        }

        @Test
        @DisplayName("ORG_OWNER không khoá được tài khoản của ai — đây là quyết định của nền tảng")
        void chu_to_chuc_khong_khoa_duoc() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.disable(newUser(), "x")).is(errorCode(ErrorCode.Common.FORBIDDEN));
            assertThatThrownBy(() -> handler.enable(newUser())).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(users);
        }

        @Test
        @DisplayName("người dùng không tồn tại thì USER_NOT_FOUND")
        void khong_ton_tai() {
            when(users.findById(any())).thenReturn(Optional.empty());
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.disable(newUser(), "x")).is(errorCode(IdentityErrorCode.USER_NOT_FOUND));
            assertThatThrownBy(() -> handler.enable(newUser())).is(errorCode(IdentityErrorCode.USER_NOT_FOUND));
        }

        @Test
        @DisplayName("khôi phục không lùi mốc thu hồi — token bị vô hiệu lúc khoá vẫn phải chết")
        void khoi_phuc() {
            UserId target = newUser();
            when(users.findById(target)).thenReturn(Optional.of(disabledUser(target, "a@example.com")));
            TenantContextFixture.superAdmin(newUser());

            handler.enable(target);

            verify(users).setStatus(target, UserStatus.ACTIVE);
            verify(users, never()).revokeTokensIssuedBefore(any(), any());
            verify(audit).record(eq("USER_ENABLED"), eq("user"), eq(target.value()), any(), any());
        }

        @Test
        @DisplayName("khôi phục tài khoản đang hoạt động là no-op")
        void khoi_phuc_tai_khoan_dang_hoat_dong() {
            UserId target = newUser();
            when(users.findById(target)).thenReturn(Optional.of(user(target, "a@example.com")));
            TenantContextFixture.superAdmin(newUser());

            handler.enable(target);

            verify(users, never()).setStatus(any(), any());
            verifyNoInteractions(audit);
        }
    }

    @Nested
    @DisplayName("ghi nhật ký hộ service khác")
    class NhatKyNgoai {

        private final ExternalAuditWriter writer = mock(ExternalAuditWriter.class);
        private final RecordExternalAuditHandler handler = new RecordExternalAuditHandler(writer);

        private RecordExternalAuditHandler.Command command(
                String action, String entityType, Map<String, Object> details) {
            return new RecordExternalAuditHandler.Command(
                    UUID.randomUUID(), ORG.value(), action, entityType, UUID.randomUUID(), details);
        }

        @Test
        @DisplayName("hành động trong danh sách đóng được ghi nguyên vẹn")
        void hanh_dong_hop_le() {
            RecordExternalAuditHandler.Command command = command("BUYER_LIST_EXPORTED", "event", Map.of("rows", 12));

            handler.handle(command);

            verify(writer)
                    .recordFor(
                            command.actorUserId(),
                            command.organizationId(),
                            "BUYER_LIST_EXPORTED",
                            "event",
                            command.entityId(),
                            Map.of("rows", 12));
        }

        @ParameterizedTest
        @ValueSource(strings = {"MEMBER_REMOVED", "ORGANIZATION_SUSPENDED", "buyer_list_exported", ""})
        @DisplayName("hành động ngoài danh sách bị từ chối — không dựng được dòng nhật ký giả của identity")
        void hanh_dong_ngoai_danh_sach(String action) {
            assertThatThrownBy(() -> handler.handle(command(action, "event", Map.of())))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            verifyNoInteractions(writer);
        }

        @Test
        @DisplayName("thiếu người thực hiện, tổ chức hay loại đối tượng đều bị từ chối")
        void thieu_truong() {
            assertThatThrownBy(() -> handler.handle(new RecordExternalAuditHandler.Command(
                            null, ORG.value(), "BUYER_LIST_EXPORTED", "event", null, null)))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            assertThatThrownBy(() -> handler.handle(new RecordExternalAuditHandler.Command(
                            UUID.randomUUID(), null, "BUYER_LIST_EXPORTED", "event", null, null)))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            assertThatThrownBy(() -> handler.handle(command("BUYER_LIST_EXPORTED", "  ", null)))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            assertThatThrownBy(() -> handler.handle(new RecordExternalAuditHandler.Command(
                            UUID.randomUUID(), ORG.value(), null, "event", null, null)))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            verifyNoInteractions(writer);
        }

        @Test
        @DisplayName("chi tiết đúng 20 mục thì được, 21 mục thì bị từ chối")
        void tran_chi_tiet() {
            Map<String, Object> twenty = new HashMap<>();
            for (int i = 0; i < 20; i++) {
                twenty.put("k" + i, i);
            }
            handler.handle(command("BUYER_LIST_EXPORTED", "event", twenty));

            Map<String, Object> twentyOne = new HashMap<>(twenty);
            twentyOne.put("k20", 20);
            assertThatThrownBy(() -> handler.handle(command("BUYER_LIST_EXPORTED", "event", twentyOne)))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));

            ArgumentCaptor<Map<String, Object>> details = detailsCaptor();
            verify(writer).recordFor(any(), any(), anyString(), anyString(), any(), details.capture());
            assertThat(details.getValue()).hasSize(20);
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<Map<String, Object>> detailsCaptor() {
            return ArgumentCaptor.forClass(Map.class);
        }
    }
}
