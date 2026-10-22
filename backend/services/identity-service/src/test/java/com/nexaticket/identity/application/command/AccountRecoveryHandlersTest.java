// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.NOW;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.OTHER_ORG;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.SessionRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/**
 * Thu hồi phiên và gửi hộ thư đặt lại mật khẩu — hai lệnh chung một luật uỷ quyền: superadmin với
 * mọi người, quản trị viên tổ chức chỉ với thành viên của chính tổ chức mình.
 *
 * <p>Thiếu bước kiểm thành viên thì một quyền nghe rất hẹp ({@code ORG_SESSION_REVOKE}) trở thành
 * quyền đá bất kỳ ai ra khỏi hệ thống — kể cả superadmin — chỉ cần biết id.
 */
class AccountRecoveryHandlersTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final IdentityProviderPort identityProvider = mock(IdentityProviderPort.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final UserId admin = newUser();
    private final UserId member = newUser();
    private final UserId outsider = newUser();

    @BeforeEach
    void organizationWithMember() {
        Map<UserId, Role> members = new LinkedHashMap<>();
        members.put(admin, Role.ORG_ADMIN);
        members.put(member, Role.CHECKIN_STAFF);
        when(organizations.findById(ORG)).thenReturn(Optional.of(organization(ORG, members)));
        when(users.findById(member)).thenReturn(Optional.of(user(member, "nv@example.com")));
        when(users.findById(outsider)).thenReturn(Optional.of(user(outsider, "ngoai@example.com")));
    }

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    @Nested
    @DisplayName("thu hồi phiên")
    class ThuHoiPhien {

        private final RevokeSessionsHandler handler =
                new RevokeSessionsHandler(users, sessions, organizations, audit, CLOCK);

        @Test
        @DisplayName("quản trị viên thu hồi toàn bộ phiên của thành viên: mốc thu hồi là đúng lúc này")
        void thu_hoi_toan_bo() {
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            handler.revokeAll(member, ORG, "Mất máy");

            verify(users).revokeTokensIssuedBefore(member, NOW);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
            verify(audit).record(eq("SESSIONS_REVOKED"), eq("user"), eq(member.value()), isNull(), after.capture());
            assertThat(after.getValue()).containsEntry("reason", "Mất máy").containsEntry("sessionId", null);
        }

        @Test
        @DisplayName("người ngoài tổ chức trả NOT_A_MEMBER (404) — không thu hồi được phiên của superadmin")
        void nguoi_ngoai() {
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.revokeAll(outsider, ORG, null))
                    .is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
            verify(users, never()).revokeTokensIssuedBefore(any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("quản trị viên tổ chức khác không thu hồi được phiên của thành viên tổ chức này")
        void quan_tri_to_chuc_khac() {
            TenantContextFixture.member(newUser(), OTHER_ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.revokeAll(member, ORG, null)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verify(users, never()).revokeTokensIssuedBefore(any(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"EVENT_MANAGER", "CHECKIN_STAFF"})
        @DisplayName("vai trò không có ORG_SESSION_REVOKE bị từ chối")
        void khong_co_quyen(Role role) {
            TenantContextFixture.member(newUser(), ORG, role);

            assertThatThrownBy(() -> handler.revokeAll(member, ORG, null)).is(errorCode(ErrorCode.Common.FORBIDDEN));
        }

        @Test
        @DisplayName("đường nền tảng (không tổ chức) chỉ superadmin, kể cả ORG_OWNER cũng không")
        void duong_nen_tang() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.revokeAll(outsider, null, null)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            assertThatThrownBy(() -> handler.revokeSession(outsider, "sid-1", null, null))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
        }

        @Test
        @DisplayName("thu hồi một phiên: lưu sid kèm người thu hồi, hạn 45 ngày, và dọn hàng quá hạn")
        void thu_hoi_mot_phien() {
            UserId superAdmin = newUser();
            TenantContextFixture.superAdmin(superAdmin);

            handler.revokeSession(outsider, "sid-thiet-bi-2", null, "Quên đăng xuất");

            verify(sessions)
                    .revoke("sid-thiet-bi-2", outsider, superAdmin, "Quên đăng xuất", NOW.plus(Duration.ofDays(45)));
            verify(sessions).purgeExpired(NOW);
            verify(users, never()).revokeTokensIssuedBefore(any(), any());
            verify(audit).record(eq("SESSION_REVOKED"), eq("user"), eq(outsider.value()), isNull(), any());
        }

        @Test
        @DisplayName("người dùng không tồn tại thì USER_NOT_FOUND, không ghi gì")
        void khong_ton_tai() {
            UserId ghost = newUser();
            when(users.findById(ghost)).thenReturn(Optional.empty());
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.revokeAll(ghost, null, null))
                    .is(errorCode(IdentityErrorCode.USER_NOT_FOUND));
            assertThatThrownBy(() -> handler.revokeSession(ghost, "sid", null, null))
                    .is(errorCode(IdentityErrorCode.USER_NOT_FOUND));
            verifyNoInteractions(sessions);
        }
    }

    @Nested
    @DisplayName("gửi hộ thư đặt lại mật khẩu")
    class DatLaiMatKhau {

        private final SendPasswordResetHandler handler =
                new SendPasswordResetHandler(users, organizations, identityProvider, audit);

        @Test
        @DisplayName("gửi cho thành viên qua Keycloak, nhật ký không chứa email")
        void gui_cho_thanh_vien() {
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            handler.handle(member, ORG);

            verify(identityProvider).sendPasswordResetEmail("sub-" + member.value(), null, null);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
            verify(audit).record(eq("PASSWORD_RESET_SENT"), eq("user"), eq(member.value()), isNull(), after.capture());
            assertThat(after.getValue()).containsEntry("scope", "ORGANIZATION");
            assertThat(after.getValue().values()).doesNotContain("nv@example.com");
        }

        @Test
        @DisplayName("người ngoài tổ chức: NOT_A_MEMBER, Keycloak không được gọi")
        void nguoi_ngoai() {
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.handle(outsider, ORG)).is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("EVENT_MANAGER không gửi được")
        void quan_ly_su_kien() {
            TenantContextFixture.member(newUser(), ORG, Role.EVENT_MANAGER);

            assertThatThrownBy(() -> handler.handle(member, ORG)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("tài khoản bị vô hiệu hoá thì USER_DISABLED — đặt lại xong vẫn không vào được")
        void tai_khoan_bi_khoa() {
            UserId disabled = newUser();
            when(users.findById(disabled)).thenReturn(Optional.of(disabledUser(disabled, "x@example.com")));
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.handle(disabled, null)).is(errorCode(IdentityErrorCode.USER_DISABLED));
            verifyNoInteractions(identityProvider);
        }

        @Test
        @DisplayName("Keycloak không trả lời: 503 với thông điệp chung, không lộ nguyên văn lỗi của Keycloak")
        void keycloak_im_lang() {
            TenantContextFixture.superAdmin(newUser());
            doThrow(new IdentityProviderPort.IdentityProviderUnavailableException(
                            "Keycloak trả 400 BAD_REQUEST: {\"error\":\"Invalid redirect uri\",\"realm\":\"nexaticket\"}",
                            null))
                    .when(identityProvider)
                    .sendPasswordResetEmail(anyString(), any(), any());

            assertThatThrownBy(() -> handler.handle(outsider, null))
                    .is(errorCode(IdentityErrorCode.PASSWORD_RESET_UNAVAILABLE))
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain("redirect")
                            .doesNotContain("realm")
                            .doesNotContain("Keycloak trả"));
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("đường nền tảng ghi phạm vi PLATFORM")
        void pham_vi_nen_tang() {
            TenantContextFixture.superAdmin(newUser());

            handler.handle(outsider, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
            verify(audit).record(anyString(), anyString(), any(), any(), after.capture());
            assertThat(after.getValue()).containsEntry("scope", "PLATFORM");
        }
    }
}
