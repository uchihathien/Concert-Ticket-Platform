// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link TenantContext} và {@link TenantScope}: cửa quyền chính của hệ thống.
 *
 * <p>Ma trận ở đây là bản chạy được của {@code rbac-permission-matrix.md}: ai qua được cửa nào, và
 * khi bị chặn thì nhận mã gì — 401 cho người chưa đăng nhập, 403 cho người thiếu quyền, 404 cho
 * người ngoài tổ chức (403 sẽ xác nhận tổ chức đó tồn tại).
 */
class TenantContextTest {

    private static final TenantId ORG_A = TenantId.of(UUID.fromString("aaaaaaaa-0000-4000-a000-000000000001"));
    private static final TenantId ORG_B = TenantId.of(UUID.fromString("bbbbbbbb-0000-4000-a000-000000000002"));
    private static final UserId USER = UserId.of(UUID.fromString("cccccccc-0000-4000-a000-000000000003"));

    @AfterEach
    void don_context() {
        TenantContext.clear();
    }

    private static void signedIn(Map<TenantId, Role> memberships, TenantId active, boolean superAdmin) {
        TenantContext.set(new TenantScope(USER, memberships, active, superAdmin));
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.errorCode();
        }
        throw new AssertionError("Không ném ApiException");
    }

    @Nested
    @DisplayName("Ngữ cảnh mặc định")
    class Defaults {

        @Test
        @DisplayName("chưa set gì: ẩn danh, không có tổ chức, không phải superadmin")
        void mac_dinh_la_an_danh() {
            TenantScope scope = TenantContext.current();

            assertThat(scope.isAuthenticated()).isFalse();
            assertThat(scope.tenants()).isEmpty();
            assertThat(scope.superAdmin()).isFalse();
            assertThat(scope.activeTenant()).isNull();
        }

        @Test
        @DisplayName("clear() đưa luồng về ẩn danh — không mang danh tính sang request sau")
        void clear_ve_an_danh() {
            signedIn(Map.of(ORG_A, Role.ORG_ADMIN), ORG_A, false);

            TenantContext.clear();

            assertThat(TenantContext.current().isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("ngữ cảnh là của từng luồng: luồng khác không thấy danh tính của luồng này")
        void ngu_canh_theo_luong() throws Exception {
            signedIn(Map.of(ORG_A, Role.ORG_ADMIN), ORG_A, true);
            boolean[] otherSaw = new boolean[1];

            Thread other =
                    new Thread(() -> otherSaw[0] = TenantContext.current().isAuthenticated());
            other.start();
            other.join();

            assertThat(otherSaw[0]).isFalse();
        }
    }

    @Nested
    @DisplayName("requireAuthenticated / requireActiveTenant / requireSuperAdmin")
    class BasicGates {

        @Test
        @DisplayName("ẩn danh: UNAUTHENTICATED (401)")
        void an_danh_bi_401() {
            assertThat(codeOf(TenantContext::requireAuthenticated)).isEqualTo(ErrorCode.Common.UNAUTHENTICATED);
            assertThat(codeOf(TenantContext::requireActiveTenant)).isEqualTo(ErrorCode.Common.UNAUTHENTICATED);
            assertThat(codeOf(TenantContext::requireSuperAdmin)).isEqualTo(ErrorCode.Common.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("đã đăng nhập nhưng không có tổ chức đang thao tác: FORBIDDEN")
        void khong_co_to_chuc_dang_thao_tac() {
            signedIn(Map.of(ORG_A, Role.ORG_ADMIN, ORG_B, Role.ORG_ADMIN), null, false);

            assertThat(codeOf(TenantContext::requireActiveTenant)).isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("có tổ chức đang thao tác: trả đúng tổ chức đó")
        void co_to_chuc_dang_thao_tac() {
            signedIn(Map.of(ORG_A, Role.EVENT_MANAGER), ORG_A, false);

            assertThat(TenantContext.requireActiveTenant()).isEqualTo(ORG_A);
        }

        @Test
        @DisplayName("người thường gọi cửa superadmin: FORBIDDEN; superadmin thì qua")
        void cua_superadmin() {
            signedIn(Map.of(ORG_A, Role.ORG_OWNER), ORG_A, false);
            assertThat(codeOf(TenantContext::requireSuperAdmin)).isEqualTo(ErrorCode.Common.FORBIDDEN);

            signedIn(Map.of(), null, true);
            TenantContext.requireSuperAdmin();
        }
    }

    @Nested
    @DisplayName("requirePermission — quyền phạm vi tổ chức")
    class OrgPermission {

        @Test
        @DisplayName("vai trò có quyền trong ĐÚNG tổ chức: qua")
        void co_quyen_dung_to_chuc() {
            signedIn(Map.of(ORG_A, Role.EVENT_MANAGER), ORG_A, false);

            TenantScope scope = TenantContext.requirePermission(Permission.CATALOG_MANAGE, ORG_A);

            assertThat(scope.userId()).isEqualTo(USER);
        }

        @Test
        @DisplayName("có quyền ở tổ chức A không cho quyền ở tổ chức B")
        void quyen_khong_lan_sang_to_chuc_khac() {
            signedIn(Map.of(ORG_A, Role.ORG_OWNER, ORG_B, Role.CHECKIN_STAFF), ORG_B, false);

            assertThat(codeOf(() -> TenantContext.requirePermission(Permission.CATALOG_MANAGE, ORG_B)))
                    .isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("không phải thành viên tổ chức: FORBIDDEN")
        void khong_phai_thanh_vien() {
            signedIn(Map.of(ORG_A, Role.ORG_ADMIN), ORG_A, false);

            assertThat(codeOf(() -> TenantContext.requirePermission(Permission.CHECKIN_SCAN, ORG_B)))
                    .isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("EVENT_MANAGER không quản lý được thành viên — chặn tự nâng quyền qua trung gian")
        void event_manager_khong_quan_ly_thanh_vien() {
            signedIn(Map.of(ORG_A, Role.EVENT_MANAGER), ORG_A, false);

            assertThat(codeOf(() -> TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, ORG_A)))
                    .isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("superadmin đi qua mọi cửa của tổ chức, kể cả tổ chức mình không thuộc")
        void superadmin_qua_moi_cua_to_chuc() {
            signedIn(Map.of(), null, true);

            TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, ORG_B);
        }

        @Test
        @DisplayName("ẩn danh: UNAUTHENTICATED trước khi xét quyền")
        void an_danh_401() {
            assertThat(codeOf(() -> TenantContext.requirePermission(Permission.CHECKIN_SCAN, ORG_A)))
                    .isEqualTo(ErrorCode.Common.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("hỏi quyền PLATFORM_* kèm tổ chức là lỗi lập trình, kể cả với superadmin")
        void quyen_nen_tang_kem_to_chuc_la_loi_lap_trinh() {
            signedIn(Map.of(), null, true);

            assertThatThrownBy(() -> TenantContext.requirePermission(Permission.PLATFORM_FINANCE_VIEW, ORG_A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PLATFORM_FINANCE_VIEW");
        }
    }

    @Nested
    @DisplayName("requirePlatformPermission — quyền phạm vi nền tảng")
    class PlatformPermission {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Permission.class, mode = EnumSource.Mode.MATCH_ALL, names = "PLATFORM_.*")
        @DisplayName("superadmin có mọi quyền nền tảng")
        void superadmin_co_moi_quyen_nen_tang(Permission permission) {
            signedIn(Map.of(), null, true);

            TenantContext.requirePlatformPermission(permission);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Permission.class, mode = EnumSource.Mode.MATCH_ALL, names = "PLATFORM_.*")
        @DisplayName("chủ sở hữu tổ chức KHÔNG có quyền nền tảng nào")
        void org_owner_khong_co_quyen_nen_tang(Permission permission) {
            signedIn(Map.of(ORG_A, Role.ORG_OWNER), ORG_A, false);

            assertThat(codeOf(() -> TenantContext.requirePlatformPermission(permission)))
                    .isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("ẩn danh: UNAUTHENTICATED")
        void an_danh_401() {
            assertThat(codeOf(() -> TenantContext.requirePlatformPermission(Permission.PLATFORM_SUPPORT_HANDLE)))
                    .isEqualTo(ErrorCode.Common.UNAUTHENTICATED);
        }
    }

    @Nested
    @DisplayName("requireMember")
    class Member {

        @Test
        @DisplayName("thành viên bất kỳ vai trò nào: qua")
        void thanh_vien_qua() {
            signedIn(Map.of(ORG_A, Role.CHECKIN_STAFF), ORG_A, false);

            TenantContext.requireMember(ORG_A);
        }

        @Test
        @DisplayName("người ngoài: NOT_FOUND (404), không phải 403 — không xác nhận tổ chức tồn tại")
        void nguoi_ngoai_404() {
            signedIn(Map.of(ORG_A, Role.ORG_OWNER), ORG_A, false);

            assertThat(codeOf(() -> TenantContext.requireMember(ORG_B))).isEqualTo(ErrorCode.Common.NOT_FOUND);
        }

        @Test
        @DisplayName("superadmin: qua dù không là thành viên")
        void superadmin_qua() {
            signedIn(Map.of(), null, true);

            TenantContext.requireMember(ORG_B);
        }
    }

    @Nested
    @DisplayName("has — hỏi quyền không ném lỗi")
    class Has {

        @Test
        @DisplayName("ẩn danh: false cho mọi quyền")
        void an_danh_false() {
            assertThat(TenantContext.has(Permission.CHECKIN_SCAN, ORG_A)).isFalse();
            assertThat(TenantContext.has(Permission.PLATFORM_AUDIT_READ, null)).isFalse();
        }

        @Test
        @DisplayName("thành viên: theo đúng ma trận của vai trò trong tổ chức đó")
        void thanh_vien_theo_ma_tran() {
            signedIn(Map.of(ORG_A, Role.CHECKIN_STAFF), ORG_A, false);

            assertThat(TenantContext.has(Permission.CHECKIN_SCAN, ORG_A)).isTrue();
            assertThat(TenantContext.has(Permission.ORG_METRICS_VIEW, ORG_A)).isFalse();
            assertThat(TenantContext.has(Permission.CHECKIN_SCAN, ORG_B)).isFalse();
            assertThat(TenantContext.has(Permission.PLATFORM_SUPPORT_HANDLE, ORG_A))
                    .isFalse();
        }

        @Test
        @DisplayName("superadmin: true cho quyền tổ chức và quyền nền tảng")
        void superadmin_true() {
            signedIn(Map.of(), null, true);

            assertThat(TenantContext.has(Permission.ORG_AUDIT_READ, ORG_B)).isTrue();
            assertThat(TenantContext.has(Permission.PLATFORM_SUPPORT_HANDLE, null))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("TenantScope")
    class Scope {

        @Test
        @DisplayName("memberships được sao chép phòng thủ và không sửa được")
        void memberships_sao_chep_phong_thu() {
            Map<TenantId, Role> source = new HashMap<>();
            source.put(ORG_A, Role.CHECKIN_STAFF);
            TenantScope scope = new TenantScope(USER, source, null, false);

            source.put(ORG_B, Role.ORG_OWNER);

            assertThat(scope.tenants()).containsExactly(ORG_A);
            assertThatThrownBy(() -> scope.memberships().put(ORG_B, Role.ORG_OWNER))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("withActiveTenant giữ nguyên người dùng, membership và cờ superadmin")
        void with_active_tenant_giu_nguyen_phan_con_lai() {
            TenantScope scope = new TenantScope(USER, Map.of(ORG_A, Role.ORG_ADMIN), null, true);

            TenantScope active = scope.withActiveTenant(ORG_A);

            assertThat(active.activeTenant()).isEqualTo(ORG_A);
            assertThat(active.userId()).isEqualTo(USER);
            assertThat(active.superAdmin()).isTrue();
            assertThat(active.roleIn(ORG_A)).isEqualTo(Role.ORG_ADMIN);
            assertThat(scope.activeTenant()).as("bản gốc bất biến").isNull();
        }
    }
}
