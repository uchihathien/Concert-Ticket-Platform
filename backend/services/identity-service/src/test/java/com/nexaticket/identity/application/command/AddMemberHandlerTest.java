// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
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
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.domain.port.UserRepository.UserRecord;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Thêm một tài khoản đã đăng ký vào tổ chức: tìm đúng người bằng email/SĐT chính xác, không tạo tài
 * khoản hộ, và báo cho người được thêm qua outbox.
 */
class AddMemberHandlerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final IdentityProviderPort identityProvider = mock(IdentityProviderPort.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final AddMemberHandler handler =
            new AddMemberHandler(organizations, users, identityProvider, outbox, audit, CLOCK);

    private final UserId owner = newUser();
    private final UserId admin = newUser();
    private final UserId staff = newUser();
    private final UserId stranger = newUser();

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private Organization stubOrganization() {
        return stubOrganization(OrganizationStatus.ACTIVE);
    }

    private Organization stubOrganization(OrganizationStatus status) {
        Map<UserId, Role> members = new LinkedHashMap<>();
        members.put(owner, Role.ORG_OWNER);
        members.put(admin, Role.ORG_ADMIN);
        members.put(staff, Role.CHECKIN_STAFF);
        Organization org = organization(ORG, status, members);
        when(organizations.findById(ORG)).thenReturn(Optional.of(org));
        return org;
    }

    private UserRecord withPhone(UserId id, String email, String phone) {
        return new UserRecord(id, "sub-" + id.value(), email, "Trần Thị Lan", phone, false, UserStatus.ACTIVE, null);
    }

    @Nested
    @DisplayName("tìm tài khoản")
    class TimTaiKhoan {

        @Test
        @DisplayName("tìm theo email chính xác: trả tên, email và SĐT đã che, chưa là thành viên")
        void tim_theo_email() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail("lan.tran@gmail.com"))
                    .thenReturn(Optional.of(withPhone(stranger, "lan.tran@gmail.com", "0901234567")));

            AddMemberHandler.Candidate found = handler.find(ORG, "  Lan.Tran@Gmail.com ");

            assertThat(found.userId()).isEqualTo(stranger.toString());
            assertThat(found.fullName()).isEqualTo("Trần Thị Lan");
            assertThat(found.maskedEmail()).isEqualTo("la******@gmail.com");
            assertThat(found.maskedPhone()).isEqualTo("090****567");
            assertThat(found.alreadyMember()).isFalse();
            assertThat(found.currentRole()).isNull();
        }

        @Test
        @DisplayName("tìm theo SĐT: bỏ dấu cách, gạch, nhận cả +84")
        void tim_theo_sdt() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByPhone("84901234567"))
                    .thenReturn(List.of(withPhone(stranger, "lan.tran@gmail.com", "0901 234 567")));

            assertThat(handler.find(ORG, "+84 90-123-4567").userId()).isEqualTo(stranger.toString());
        }

        @Test
        @DisplayName("đã là thành viên thì báo cờ và vai hiện tại")
        void da_la_thanh_vien() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(user(staff, "staff@example.com")));

            AddMemberHandler.Candidate found = handler.find(ORG, "staff@example.com");

            assertThat(found.alreadyMember()).isTrue();
            assertThat(found.currentRole()).isEqualTo("CHECKIN_STAFF");
        }

        @Test
        @DisplayName("email chưa đăng ký ở đâu cả thì USER_NOT_REGISTERED")
        void email_chua_dang_ky() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.find(ORG, "moi@example.com"))
                    .is(errorCode(IdentityErrorCode.USER_NOT_REGISTERED));
            verify(users, never()).upsertByIdpSubject(any(), any(), any());
        }

        @Test
        @DisplayName("có tài khoản Keycloak nhưng chưa từng gọi API: dựng bản ghi với đúng sub của Keycloak")
        void co_tai_khoan_keycloak() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail("moi@example.com")).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail("moi@example.com"))
                    .thenReturn(Optional.of(new IdentityProviderPort.Account("kc-123", true)));
            when(users.upsertByIdpSubject("kc-123", "moi@example.com", null))
                    .thenReturn(user(stranger, "moi@example.com"));

            assertThat(handler.find(ORG, "moi@example.com").userId()).isEqualTo(stranger.toString());
        }

        @Test
        @DisplayName("tài khoản Keycloak còn chờ đặt mật khẩu không tính là đã đăng ký")
        void tai_khoan_chua_kich_hoat() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail(anyString()))
                    .thenReturn(Optional.of(new IdentityProviderPort.Account("kc-9", false)));

            assertThatThrownBy(() -> handler.find(ORG, "cho@example.com"))
                    .is(errorCode(IdentityErrorCode.USER_NOT_REGISTERED));
        }

        @Test
        @DisplayName("Keycloak không trả lời: vẫn là 'chưa có tài khoản', không phải 500")
        void keycloak_im_lang() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail(anyString()))
                    .thenThrow(new IdentityProviderPort.IdentityProviderUnavailableException("down", null));

            assertThatThrownBy(() -> handler.find(ORG, "moi@example.com"))
                    .is(errorCode(IdentityErrorCode.USER_NOT_REGISTERED));
        }

        @Test
        @DisplayName("hai tài khoản cùng SĐT: CONTACT_AMBIGUOUS, không chọn bừa người đầu tiên")
        void sdt_trung() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByPhone("0901234567"))
                    .thenReturn(List.of(
                            withPhone(stranger, "a@example.com", "0901234567"),
                            withPhone(newUser(), "b@example.com", "0901234567")));

            assertThatThrownBy(() -> handler.find(ORG, "0901234567"))
                    .is(errorCode(IdentityErrorCode.CONTACT_AMBIGUOUS));
        }

        @Test
        @DisplayName("không cho tìm mờ: chuỗi không phải email hay SĐT đủ số bị từ chối, không chạm database")
        void khong_tim_mo() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.find(ORG, "nguyen")).is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            assertThatThrownBy(() -> handler.find(ORG, "0901")).is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            assertThatThrownBy(() -> handler.find(ORG, "@gmail.com")).is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            verifyNoInteractions(users, identityProvider);
        }

        @Test
        @DisplayName("Staff không có quyền tìm")
        void staff_khong_tim_duoc() {
            stubOrganization();
            TenantContextFixture.member(staff, ORG, Role.CHECKIN_STAFF);

            assertThatThrownBy(() -> handler.find(ORG, "lan.tran@gmail.com")).is(errorCode(ErrorCode.Common.FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("thêm thành viên")
    class ThemThanhVien {

        @Test
        @DisplayName("thêm người đã đăng ký với vai Staff: lưu, ghi nhật ký, phát member.added không kèm email")
        void them_staff() {
            Organization org = stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail("lan.tran@gmail.com")).thenReturn(Optional.of(user(stranger, "lan.tran@gmail.com")));

            AddMemberHandler.Candidate added = handler.add(ORG, "lan.tran@gmail.com", Role.CHECKIN_STAFF);

            assertThat(org.findMember(stranger)).map(Membership::role).contains(Role.CHECKIN_STAFF);
            assertThat(added.alreadyMember()).isTrue();
            verify(organizations).save(org);
            verify(audit).record(eq("MEMBER_ADDED"), eq("membership"), any(), eq(null), any());
            verify(outbox)
                    .append(
                            eq(CreateOrganizationHandler.EXCHANGE),
                            eq("Membership"),
                            any(),
                            eq("member.added"),
                            eq(Map.of(
                                    "organizationId",
                                    ORG.value().toString(),
                                    "organizationName",
                                    "Tổ chức thử",
                                    "userId",
                                    stranger.toString(),
                                    "role",
                                    "CHECKIN_STAFF")));
            // Không tạo tài khoản hộ: Keycloak không bị hỏi, càng không bị ghi.
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("email chưa đăng ký: USER_NOT_REGISTERED, không lưu gì, không gửi gì")
        void chua_dang_ky() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            when(identityProvider.findAccountByEmail(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.add(ORG, "moi@example.com", Role.CHECKIN_STAFF))
                    .is(errorCode(IdentityErrorCode.USER_NOT_REGISTERED));
            verify(organizations, never()).save(any());
            verifyNoInteractions(outbox, audit);
        }

        @Test
        @DisplayName("thêm trùng người đã là thành viên: ALREADY_A_MEMBER")
        void them_trung() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(user(staff, "staff@example.com")));

            assertThatThrownBy(() -> handler.add(ORG, "staff@example.com", Role.CHECKIN_STAFF))
                    .is(errorCode(IdentityErrorCode.ALREADY_A_MEMBER));
            verify(organizations, never()).save(any());
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("ORG_ADMIN không thêm được ORG_OWNER; ORG_OWNER thì được")
        void chi_owner_them_owner() {
            stubOrganization();
            when(users.findByEmail("lan.tran@gmail.com")).thenReturn(Optional.of(user(stranger, "lan.tran@gmail.com")));

            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            assertThatThrownBy(() -> handler.add(ORG, "lan.tran@gmail.com", Role.ORG_OWNER))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));

            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);
            handler.add(ORG, "lan.tran@gmail.com", Role.ORG_OWNER);
        }

        @Test
        @DisplayName("vai ngoài phạm vi tổ chức bị từ chối")
        void vai_ngoai_pham_vi() {
            stubOrganization();
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.add(ORG, "lan.tran@gmail.com", Role.CUSTOMER))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            assertThatThrownBy(() -> handler.add(ORG, "lan.tran@gmail.com", Role.SUPER_ADMIN))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("tài khoản bị vô hiệu hoá không thêm được")
        void tai_khoan_bi_khoa() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);
            when(users.findByEmail("khoa@example.com"))
                    .thenReturn(Optional.of(disabledUser(stranger, "khoa@example.com")));

            assertThatThrownBy(() -> handler.add(ORG, "khoa@example.com", Role.CHECKIN_STAFF))
                    .is(errorCode(IdentityErrorCode.USER_DISABLED));
        }

        @Test
        @DisplayName("tổ chức bị tạm dừng: ORGANIZATION_SUSPENDED")
        void to_chuc_tam_dung() {
            stubOrganization(OrganizationStatus.SUSPENDED);
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.add(ORG, "lan.tran@gmail.com", Role.CHECKIN_STAFF))
                    .is(errorCode(IdentityErrorCode.ORGANIZATION_SUSPENDED));
        }
    }

    @Test
    @DisplayName("che email và SĐT")
    void che_thong_tin() {
        assertThat(AddMemberHandler.maskEmail("ab@x.vn")).isEqualTo("a***@x.vn");
        assertThat(AddMemberHandler.maskEmail("nguyenvan@gmail.com")).isEqualTo("ng*******@gmail.com");
        assertThat(AddMemberHandler.maskPhone("+84 901 234 567")).isEqualTo("849*****567");
        assertThat(AddMemberHandler.maskPhone("0901234567")).isEqualTo("090****567");
    }
}
