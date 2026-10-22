// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Ma trận {@link Role} x {@link Permission} — bản chạy được của {@code rbac-permission-matrix.md}.
 *
 * <p>Một ô sai trong ma trận là một lỗ hổng phân quyền chứ không phải lỗi biên dịch, nên mỗi ranh
 * giới quan trọng được ghim riêng.
 */
class RoleMatrixTest {

    private static final Set<Permission> ORG_ADMIN_SET = EnumSet.of(
            Permission.CATALOG_MANAGE,
            Permission.EVENT_PUBLISH,
            Permission.CHECKIN_SCAN,
            Permission.ORG_METRICS_VIEW,
            Permission.ORG_MEMBERS_MANAGE,
            Permission.ORG_PROFILE_MANAGE,
            Permission.ORG_LIMITS_SET,
            Permission.ORG_SESSION_REVOKE,
            Permission.ORG_AUDIT_READ);

    @Test
    @DisplayName("SUPER_ADMIN: đúng sáu quyền nền tảng, không có quyền tổ chức nào")
    void super_admin() {
        assertThat(Role.SUPER_ADMIN.permissions())
                .containsExactlyInAnyOrder(
                        Permission.PLATFORM_ORG_MANAGE,
                        Permission.PLATFORM_TEMPLATE_MANAGE,
                        Permission.PLATFORM_USER_MANAGE,
                        Permission.PLATFORM_FINANCE_VIEW,
                        Permission.PLATFORM_AUDIT_READ,
                        Permission.PLATFORM_SUPPORT_HANDLE);
    }

    @Test
    @DisplayName("ORG_OWNER và ORG_ADMIN có cùng tập quyền tổ chức")
    void owner_va_admin() {
        assertThat(Role.ORG_OWNER.permissions()).isEqualTo(ORG_ADMIN_SET);
        assertThat(Role.ORG_ADMIN.permissions()).isEqualTo(ORG_ADMIN_SET);
    }

    @Test
    @DisplayName("EVENT_MANAGER: dựng, bán, soát vé, xem số liệu; KHÔNG quản lý người")
    void event_manager() {
        assertThat(Role.EVENT_MANAGER.permissions())
                .containsExactlyInAnyOrder(
                        Permission.CATALOG_MANAGE,
                        Permission.EVENT_PUBLISH,
                        Permission.CHECKIN_SCAN,
                        Permission.ORG_METRICS_VIEW);
        assertThat(Role.EVENT_MANAGER.can(Permission.ORG_MEMBERS_MANAGE)).isFalse();
    }

    @Test
    @DisplayName("CHECKIN_STAFF chỉ quét vé; CUSTOMER không có quyền nào")
    void staff_va_customer() {
        assertThat(Role.CHECKIN_STAFF.permissions()).containsExactly(Permission.CHECKIN_SCAN);
        assertThat(Role.CUSTOMER.permissions()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "SUPER_ADMIN")
    @DisplayName("không vai trò tổ chức nào có quyền PLATFORM_* — kể cả chủ sở hữu")
    void khong_ai_ngoai_super_admin_co_quyen_nen_tang(Role role) {
        assertThat(role.permissions()).noneMatch(Permission::isPlatformScoped);
    }

    @Test
    @DisplayName("PLATFORM_SUPPORT_HANDLE chỉ SUPER_ADMIN có — ban tổ chức không đọc được chat của khách")
    void support_handle_chi_super_admin() {
        assertThat(Arrays.stream(Role.values()).filter(r -> r.can(Permission.PLATFORM_SUPPORT_HANDLE)))
                .containsExactly(Role.SUPER_ADMIN);
    }

    @Test
    @DisplayName("PLATFORM_FINANCE_VIEW chỉ SUPER_ADMIN có — tổ chức không thấy sổ cái (ADR-1010)")
    void finance_chi_super_admin() {
        assertThat(Arrays.stream(Role.values()).filter(r -> r.can(Permission.PLATFORM_FINANCE_VIEW)))
                .containsExactly(Role.SUPER_ADMIN);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Permission.class)
    @DisplayName("isPlatformScoped khớp tiền tố PLATFORM_ của tên quyền")
    void pham_vi_theo_ten(Permission permission) {
        assertThat(permission.isPlatformScoped()).isEqualTo(permission.name().startsWith("PLATFORM_"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Permission.class)
    @DisplayName("mỗi quyền được gán cho ít nhất một vai trò — không có quyền mồ côi")
    void khong_co_quyen_mo_coi(Permission permission) {
        assertThat(Arrays.stream(Role.values()).anyMatch(r -> r.can(permission)))
                .isTrue();
    }

    @Test
    @DisplayName("isOrganizationScoped: SUPER_ADMIN và CUSTOMER không gắn tổ chức, bốn vai trò còn lại có")
    void pham_vi_to_chuc() {
        assertThat(Role.SUPER_ADMIN.isOrganizationScoped()).isFalse();
        assertThat(Role.CUSTOMER.isOrganizationScoped()).isFalse();
        assertThat(Arrays.stream(Role.values()).filter(Role::isOrganizationScoped))
                .containsExactly(Role.ORG_OWNER, Role.ORG_ADMIN, Role.EVENT_MANAGER, Role.CHECKIN_STAFF);
    }

    @Test
    @DisplayName("tập quyền không sửa được từ bên ngoài")
    void tap_quyen_bat_bien() {
        assertThatThrownBy(() -> Role.CUSTOMER.permissions().add(Permission.CATALOG_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Role.ORG_ADMIN.permissions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("các hàm deprecated trả lời giống hệt hàm hỏi quyền")
    @SuppressWarnings("deprecation")
    void ham_cu_khop_ma_tran() {
        for (Role role : Role.values()) {
            assertThat(role.isAtLeastOrgAdmin()).isEqualTo(role.can(Permission.ORG_MEMBERS_MANAGE));
            assertThat(role.canManageCatalog()).isEqualTo(role.can(Permission.CATALOG_MANAGE));
            assertThat(role.canCheckIn()).isEqualTo(role.can(Permission.CHECKIN_SCAN));
        }
        assertThat(Role.EVENT_MANAGER.isAtLeastOrgAdmin()).isFalse();
        assertThat(Role.CHECKIN_STAFF.canManageCatalog()).isFalse();
    }
}
