// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Aggregate tổ chức: những luật mà {@code OrganizationTest} chưa chạm tới — vòng đời trạng thái,
 * đổi tên, và các nhánh "không phải thành viên".
 *
 * <p>Bất biến chủ sở hữu cuối cùng đã có test riêng; ở đây kiểm phía còn lại của nó: khi còn chủ
 * sở hữu khác thì hạ vai trò phải ĐƯỢC phép, nếu không tổ chức không bao giờ chuyển giao được.
 */
class OrganizationMembershipRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private static Organization newOrg() {
        return Organization.create(Slug.from("Nhà hát Lớn"), "  Nhà hát Lớn  ", NOW);
    }

    @Nested
    @DisplayName("tạo và đổi tên")
    class TaoVaDoiTen {

        @Test
        @DisplayName("tổ chức mới đang hoạt động, chưa có thành viên, tên đã cắt khoảng trắng")
        void to_chuc_moi() {
            Organization org = newOrg();

            assertThat(org.status()).isEqualTo(OrganizationStatus.ACTIVE);
            assertThat(org.isActive()).isTrue();
            assertThat(org.members()).isEmpty();
            assertThat(org.name()).isEqualTo("Nhà hát Lớn");
            assertThat(org.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("tên rỗng hoặc chỉ khoảng trắng bị từ chối, cả lúc tạo lẫn lúc đổi")
        void ten_rong() {
            assertThatThrownBy(() -> Organization.create(new Slug("x"), "   ", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Organization.create(new Slug("x"), null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);

            Organization org = newOrg();
            assertThatThrownBy(() -> org.rename(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThat(org.name()).isEqualTo("Nhà hát Lớn");
        }

        @Test
        @DisplayName("đổi tên giữ nguyên slug — slug là định danh trên URL đã phát đi")
        void doi_ten_giu_slug() {
            Organization org = newOrg();
            Slug before = org.slug();

            org.rename("  Nhà hát Thành phố ");

            assertThat(org.name()).isEqualTo("Nhà hát Thành phố");
            assertThat(org.slug()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("khoá và mở khoá")
    class KhoaMoKhoa {

        @Test
        @DisplayName("khoá rồi mở lại trở về hoạt động, thành viên còn nguyên")
        void khoa_roi_mo() {
            Organization org = newOrg();
            UserId owner = UserId.of(UUID.randomUUID());
            org.addMember(owner, Role.ORG_OWNER, NOW);

            org.suspend();
            assertThat(org.isActive()).isFalse();
            assertThat(org.status()).isEqualTo(OrganizationStatus.SUSPENDED);
            assertThat(org.findMember(owner)).isPresent();

            org.activate();
            assertThat(org.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("thành viên")
    class ThanhVien {

        @Test
        @DisplayName("đổi vai trò hay gỡ một người không thuộc tổ chức là lỗi trạng thái")
        void khong_phai_thanh_vien() {
            Organization org = newOrg();
            UserId stranger = UserId.of(UUID.randomUUID());

            assertThatThrownBy(() -> org.changeRole(stranger, Role.ORG_ADMIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Không phải thành viên");
            assertThatThrownBy(() -> org.removeMember(stranger)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("hạ một chủ sở hữu được phép khi vẫn còn chủ sở hữu khác")
        void ha_chu_so_huu_khi_con_nguoi_khac() {
            Organization org = newOrg();
            UserId first = UserId.of(UUID.randomUUID());
            UserId second = UserId.of(UUID.randomUUID());
            org.addMember(first, Role.ORG_OWNER, NOW);
            org.addMember(second, Role.ORG_OWNER, NOW);

            org.changeRole(first, Role.ORG_ADMIN);

            assertThat(org.findMember(first)).map(Membership::role).contains(Role.ORG_ADMIN);
            assertThat(org.findMember(second)).map(Membership::role).contains(Role.ORG_OWNER);
        }

        @Test
        @DisplayName("sau khi đã hạ một người, người chủ sở hữu còn lại trở thành người cuối cùng")
        void sau_khi_ha_thi_nguoi_con_lai_la_cuoi() {
            Organization org = newOrg();
            UserId first = UserId.of(UUID.randomUUID());
            UserId second = UserId.of(UUID.randomUUID());
            org.addMember(first, Role.ORG_OWNER, NOW);
            org.addMember(second, Role.ORG_OWNER, NOW);
            org.changeRole(first, Role.EVENT_MANAGER);

            assertThatThrownBy(() -> org.removeMember(second)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> org.changeRole(second, Role.ORG_ADMIN)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("phong chủ sở hữu cuối cùng lên chính vai trò đó không bị coi là hạ cấp")
        void giu_nguyen_vai_tro_chu_so_huu() {
            Organization org = newOrg();
            UserId owner = UserId.of(UUID.randomUUID());
            org.addMember(owner, Role.ORG_OWNER, NOW);

            org.changeRole(owner, Role.ORG_OWNER);

            assertThat(org.findMember(owner)).map(Membership::role).contains(Role.ORG_OWNER);
        }

        @Test
        @DisplayName("đổi vai trò giữ nguyên id membership và ngày tham gia")
        void doi_vai_tro_giu_lich_su() {
            Organization org = newOrg();
            UserId owner = UserId.of(UUID.randomUUID());
            UserId staff = UserId.of(UUID.randomUUID());
            org.addMember(owner, Role.ORG_OWNER, NOW);
            Membership before = org.addMember(staff, Role.CHECKIN_STAFF, NOW.minusSeconds(3600));

            org.changeRole(staff, Role.EVENT_MANAGER);

            Membership after = org.findMember(staff).orElseThrow();
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.joinedAt()).isEqualTo(before.joinedAt());
            assertThat(after.role()).isEqualTo(Role.EVENT_MANAGER);
        }

        @Test
        @DisplayName("đổi vai trò sang vai trò ngoài phạm vi tổ chức bị từ chối, giống như lúc thêm")
        void doi_sang_vai_tro_ngoai_pham_vi() {
            // addMember đã chặn SUPER_ADMIN/CUSTOMER; changeRole thì chưa. Hệ quả: PATCH vai trò
            // thành SUPER_ADMIN lọt qua aggregate, rồi đâm vào CHECK ck_member_role của database và
            // thành 500 — thay vì một lỗi nghiệp vụ nói rõ vai trò không hợp lệ.
            Organization org = newOrg();
            UserId owner = UserId.of(UUID.randomUUID());
            UserId admin = UserId.of(UUID.randomUUID());
            org.addMember(owner, Role.ORG_OWNER, NOW);
            org.addMember(admin, Role.ORG_ADMIN, NOW);

            assertThatThrownBy(() -> org.changeRole(admin, Role.SUPER_ADMIN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> org.changeRole(admin, Role.CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
            assertThat(org.findMember(admin)).map(Membership::role).contains(Role.ORG_ADMIN);
        }

        @Test
        @DisplayName("danh sách thành viên trả ra ngoài không sửa được — bất biến chỉ giữ được qua aggregate")
        void danh_sach_chi_doc() {
            Organization org = newOrg();
            org.addMember(UserId.of(UUID.randomUUID()), Role.ORG_OWNER, NOW);

            assertThatThrownBy(() -> org.members().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(org.members()).hasSize(1);
        }
    }
}
