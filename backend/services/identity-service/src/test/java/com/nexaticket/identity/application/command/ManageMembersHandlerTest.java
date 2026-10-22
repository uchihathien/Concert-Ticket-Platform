// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.OTHER_ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Đổi vai trò và gỡ thành viên: ba luật uỷ quyền riêng (ORG_ADMIN+, không tự đụng mình, chỉ OWNER
 * phong OWNER) cộng bất biến chủ sở hữu cuối cùng của aggregate.
 */
class ManageMembersHandlerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ManageMembersHandler handler = new ManageMembersHandler(organizations, audit);

    private final UserId owner = newUser();
    private final UserId admin = newUser();
    private final UserId manager = newUser();
    private final UserId staff = newUser();

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private Organization stubOrganization() {
        Map<UserId, Role> members = new LinkedHashMap<>();
        members.put(owner, Role.ORG_OWNER);
        members.put(admin, Role.ORG_ADMIN);
        members.put(manager, Role.EVENT_MANAGER);
        members.put(staff, Role.CHECKIN_STAFF);
        Organization org = organization(ORG, members);
        when(organizations.findById(ORG)).thenReturn(Optional.of(org));
        return org;
    }

    @Nested
    @DisplayName("đổi vai trò")
    class DoiVaiTro {

        @Test
        @DisplayName("ORG_ADMIN đổi được vai trò người khác, có ghi nhật ký trước/sau")
        void admin_doi_duoc() {
            Organization org = stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            handler.changeRole(ORG, staff, Role.EVENT_MANAGER);

            assertThat(org.findMember(staff)).map(Membership::role).contains(Role.EVENT_MANAGER);
            verify(organizations).save(org);
            verify(audit)
                    .record(
                            "MEMBER_ROLE_CHANGED",
                            "membership",
                            staff.value(),
                            Map.of("role", "CHECKIN_STAFF"),
                            Map.of("role", "EVENT_MANAGER"));
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"EVENT_MANAGER", "CHECKIN_STAFF"})
        @DisplayName("vai trò dưới ORG_ADMIN không đổi được vai trò của ai")
        void vai_tro_thap_bi_tu_choi(Role role) {
            stubOrganization();
            TenantContextFixture.member(newUser(), ORG, role);

            assertThatThrownBy(() -> handler.changeRole(ORG, staff, Role.ORG_ADMIN))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations, audit);
        }

        @Test
        @DisplayName("không tự nâng vai trò của chính mình, kể cả khi là chủ sở hữu")
        void khong_tu_doi_vai_tro() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.changeRole(ORG, admin, Role.ORG_OWNER))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("ORG_ADMIN không phong được ai lên ORG_OWNER — đó là tự nâng quyền qua trung gian")
        void admin_khong_phong_owner() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.changeRole(ORG, manager, Role.ORG_OWNER))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("ORG_ADMIN không hạ được một ORG_OWNER, kể cả khi tổ chức còn chủ sở hữu khác")
        void admin_khong_ha_owner() {
            // Mặt kia của luật 3 (identity-api.md): nếu ORG_ADMIN không được phong OWNER vì đó là tự
            // nâng quyền, thì cũng không được hạ OWNER — hạ hết những người đứng trên mình là cùng
            // một kiểu chiếm tổ chức, chỉ đi theo chiều ngược lại.
            Organization org = stubOrganization();
            UserId secondOwner = newUser();
            org.addMember(secondOwner, Role.ORG_OWNER, com.nexaticket.identity.application.support.Fixtures.NOW);
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.changeRole(ORG, owner, Role.EVENT_MANAGER))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            assertThatThrownBy(() -> handler.remove(ORG, owner)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            assertThat(org.findMember(owner)).map(Membership::role).contains(Role.ORG_OWNER);
        }

        @Test
        @DisplayName("ORG_OWNER phong được người khác lên ORG_OWNER")
        void owner_phong_owner() {
            Organization org = stubOrganization();
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            handler.changeRole(ORG, admin, Role.ORG_OWNER);

            assertThat(org.findMember(admin)).map(Membership::role).contains(Role.ORG_OWNER);
        }

        @Test
        @DisplayName("superadmin phong được ORG_OWNER dù không là thành viên")
        void superadmin_phong_owner() {
            Organization org = stubOrganization();
            TenantContextFixture.superAdmin(newUser());

            handler.changeRole(ORG, admin, Role.ORG_OWNER);

            assertThat(org.findMember(admin)).map(Membership::role).contains(Role.ORG_OWNER);
        }

        @Test
        @DisplayName("hạ chủ sở hữu cuối cùng thành LAST_OWNER (409), không phải 500")
        void ha_chu_so_huu_cuoi() {
            stubOrganization();
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.changeRole(ORG, owner, Role.ORG_ADMIN))
                    .is(errorCode(IdentityErrorCode.LAST_OWNER));
            verify(organizations, never()).save(any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("người không thuộc tổ chức thì NOT_A_MEMBER")
        void khong_phai_thanh_vien() {
            stubOrganization();
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.changeRole(ORG, newUser(), Role.EVENT_MANAGER))
                    .is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
        }

        @Test
        @DisplayName("tổ chức không tồn tại thì ORGANIZATION_NOT_FOUND")
        void to_chuc_khong_ton_tai() {
            when(organizations.findById(ORG)).thenReturn(Optional.empty());
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.changeRole(ORG, staff, Role.EVENT_MANAGER))
                    .is(errorCode(IdentityErrorCode.ORGANIZATION_NOT_FOUND));
        }

        @Test
        @DisplayName("ORG_ADMIN của tổ chức khác bị từ chối dù có đúng quyền ở nhà mình")
        void admin_to_chuc_khac() {
            stubOrganization();
            TenantContextFixture.member(newUser(), OTHER_ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.changeRole(ORG, staff, Role.EVENT_MANAGER))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations);
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"SUPER_ADMIN", "CUSTOMER"})
        @DisplayName("vai trò ngoài phạm vi tổ chức bị từ chối bằng lỗi 400, không lưu xuống database")
        void vai_tro_ngoai_pham_vi(Role role) {
            // RoleRequest chỉ kiểm @NotNull, nên SUPER_ADMIN đi thẳng tới đây. Aggregate không chặn
            // trong changeRole, repository lưu, và CHECK ck_member_role của PostgreSQL ném lỗi —
            // người gọi nhận 500 thay vì một lỗi kiểm tra đầu vào.
            stubOrganization();
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.changeRole(ORG, manager, role))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            verify(organizations, never()).save(any());
        }
    }

    @Nested
    @DisplayName("gỡ thành viên")
    class GoThanhVien {

        @Test
        @DisplayName("gỡ người khác thì lưu aggregate và ghi nhật ký vai trò cũ")
        void go_nguoi_khac() {
            Organization org = stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            handler.remove(ORG, manager);

            assertThat(org.findMember(manager)).isEmpty();
            verify(organizations).save(org);
            verify(audit)
                    .record(
                            eq("MEMBER_REMOVED"),
                            eq("membership"),
                            eq(manager.value()),
                            eq(Map.of("role", "EVENT_MANAGER")),
                            isNull());
        }

        @Test
        @DisplayName("không tự gỡ chính mình")
        void khong_tu_go() {
            stubOrganization();
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.remove(ORG, owner)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations);
        }

        @Test
        @DisplayName("không gỡ được chủ sở hữu cuối cùng, kể cả superadmin")
        void khong_go_chu_so_huu_cuoi() {
            Organization org = stubOrganization();
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.remove(ORG, owner)).is(errorCode(IdentityErrorCode.LAST_OWNER));
            assertThat(org.findMember(owner)).isPresent();
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("EVENT_MANAGER không gỡ được ai")
        void quan_ly_su_kien_khong_go_duoc() {
            stubOrganization();
            TenantContextFixture.member(manager, ORG, Role.EVENT_MANAGER);

            assertThatThrownBy(() -> handler.remove(ORG, staff)).is(errorCode(ErrorCode.Common.FORBIDDEN));
        }

        @Test
        @DisplayName("gỡ người không thuộc tổ chức thì NOT_A_MEMBER")
        void go_nguoi_ngoai() {
            stubOrganization();
            TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.remove(ORG, newUser())).is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
        }
    }
}
