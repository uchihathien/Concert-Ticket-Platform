// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ma trận phân quyền, đối chiếu với {@code docs/00-discovery/rbac-permission-matrix.md}.
 *
 * <p>Không dựng Spring: ma trận là hằng số của hệ thống, và một bộ test chạy trong mili giây là bộ
 * test người ta chạy lại sau mỗi lần sửa vai trò.
 *
 * <p>Điều đáng kiểm không phải "ORG_ADMIN có quản được thành viên không" — đọc code là thấy — mà là
 * những chỗ <b>thiếu</b>: quyền nào KHÔNG được gán cho ai. Một quyền lỡ tay gán thừa không làm test
 * nào đỏ, trừ khi có test viết riêng cho việc đó.
 */
class PermissionMatrixTest {

    @Test
    @DisplayName("EVENT_MANAGER dựng được sự kiện nhưng không đụng được tới người và tiền")
    void event_manager_khong_quan_ly_nguoi() {
        // Cho EVENT_MANAGER quyền thêm thành viên là cho họ tự nâng quyền qua trung gian: mời một
        // tài khoản mình kiểm soát vào với vai trò ORG_ADMIN rồi dùng tài khoản đó.
        assertThat(Role.EVENT_MANAGER.can(Permission.CATALOG_MANAGE)).isTrue();
        assertThat(Role.EVENT_MANAGER.can(Permission.EVENT_PUBLISH)).isTrue();

        assertThat(Role.EVENT_MANAGER.can(Permission.ORG_MEMBERS_MANAGE)).isFalse();
        assertThat(Role.EVENT_MANAGER.can(Permission.ORG_LIMITS_SET)).isFalse();
        assertThat(Role.EVENT_MANAGER.can(Permission.ORG_SESSION_REVOKE)).isFalse();
        assertThat(Role.EVENT_MANAGER.can(Permission.ORG_AUDIT_READ)).isFalse();
    }

    @Test
    @DisplayName("CHECKIN_STAFF chỉ quét vé — đúng một quyền, không hơn")
    void checkin_staff_chi_quet_ve() {
        // ORG_STAFF trong tài liệu nghiệp vụ chính là vai trò này. Nhân viên thời vụ cầm máy quét ở
        // cửa, nên tập quyền của họ phải hẹp nhất có thể.
        assertThat(Role.CHECKIN_STAFF.permissions()).containsExactly(Permission.CHECKIN_SCAN);
    }

    @Test
    @DisplayName("CUSTOMER không có quyền nào trong phạm vi tổ chức")
    void customer_khong_co_quyen_to_chuc() {
        assertThat(Role.CUSTOMER.permissions()).isEmpty();
        assertThat(Role.CUSTOMER.isOrganizationScoped()).isFalse();
    }

    @Test
    @DisplayName("không vai trò nào của tổ chức chạm được vào quyền của nền tảng")
    void vai_tro_to_chuc_khong_cham_quyen_nen_tang() {
        // Đây là ranh giới cứng của ADR-1010, và nó phải đúng cho MỌI vai trò của tổ chức — kể cả
        // vai trò được thêm sau này, nên test duyệt cả enum thay vì liệt kê tay.
        for (Role role : Role.values()) {
            if (role == Role.SUPER_ADMIN) {
                continue;
            }
            assertThat(role.permissions())
                    .as("Vai trò %s không được có quyền nền tảng", role)
                    .noneMatch(Permission::isPlatformScoped);
        }
    }

    @Test
    @DisplayName("chỉ SUPER_ADMIN thấy được miền tài chính")
    void chi_superadmin_thay_tien() {
        // Tổ chức chỉ được thấy số vé bán và tiền đã bán; hoa hồng, số dư, lịch chi trả thì không.
        // Cố ý không tồn tại phiên bản phạm vi tổ chức của quyền này.
        assertThat(Arrays.stream(Role.values()).filter(r -> r.can(Permission.PLATFORM_FINANCE_VIEW)))
                .containsExactly(Role.SUPER_ADMIN);
    }

    @Test
    @DisplayName("mọi quyền đều được gán cho ít nhất một vai trò")
    void khong_co_quyen_mo_coi() {
        // Một quyền không ai có là một quyền chặn hết mọi người — và nó sẽ được phát hiện bởi người
        // dùng, ở màn hình không bấm được, chứ không phải ở đây.
        for (Permission permission : Permission.values()) {
            assertThat(Arrays.stream(Role.values()).anyMatch(role -> role.can(permission)))
                    .as("Quyền %s chưa được gán cho vai trò nào", permission)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("ba hàm boolean cũ trả lời giống hệt ma trận")
    void ham_cu_dong_bo_voi_ma_tran() {
        // Chúng vẫn được gọi từ ticketing và từ code cũ. Định nghĩa lại chúng theo ma trận là để
        // không còn hai nguồn chân lý; test này giữ cho việc đó đúng nếu ai đó sửa ma trận.
        for (Role role : Role.values()) {
            assertThat(role.isAtLeastOrgAdmin()).isEqualTo(role == Role.ORG_OWNER || role == Role.ORG_ADMIN);
            assertThat(role.canManageCatalog())
                    .isEqualTo(role == Role.ORG_OWNER || role == Role.ORG_ADMIN || role == Role.EVENT_MANAGER);
            assertThat(role.canCheckIn()).isEqualTo(role.isOrganizationScoped());
        }
    }
}
