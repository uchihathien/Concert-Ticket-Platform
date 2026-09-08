// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private static Organization newOrg() {
        return Organization.create(Slug.from("Nhà hát Lớn Hà Nội"), "Nhà hát Lớn Hà Nội", NOW);
    }

    @Test
    void slug_bo_dau_tieng_viet() {
        assertThat(Slug.from("Nhà hát Lớn Hà Nội").value()).isEqualTo("nha-hat-lon-ha-noi");
        assertThat(Slug.from("Sân vận động Mỹ Đình").value()).isEqualTo("san-van-dong-my-dinh");
        assertThat(Slug.from("Đêm nhạc ĐỎ").value()).isEqualTo("dem-nhac-do");
    }

    @Test
    void khong_the_xoa_chu_so_huu_cuoi_cung() {
        Organization org = newOrg();
        UserId owner = UserId.of(UUID.randomUUID());
        org.addMember(owner, Role.ORG_OWNER, NOW);

        assertThatThrownBy(() -> org.removeMember(owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chủ sở hữu cuối cùng");
    }

    @Test
    void khong_the_ha_vai_tro_chu_so_huu_cuoi_cung() {
        Organization org = newOrg();
        UserId owner = UserId.of(UUID.randomUUID());
        org.addMember(owner, Role.ORG_OWNER, NOW);

        assertThatThrownBy(() -> org.changeRole(owner, Role.EVENT_MANAGER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void xoa_duoc_khi_con_chu_so_huu_khac() {
        Organization org = newOrg();
        UserId first = UserId.of(UUID.randomUUID());
        UserId second = UserId.of(UUID.randomUUID());
        org.addMember(first, Role.ORG_OWNER, NOW);
        org.addMember(second, Role.ORG_OWNER, NOW);

        org.removeMember(first);

        assertThat(org.members()).hasSize(1);
        assertThat(org.findMember(second)).isPresent();
    }

    @Test
    void vai_tro_ngoai_pham_vi_to_chuc_bi_tu_choi() {
        Organization org = newOrg();
        UserId user = UserId.of(UUID.randomUUID());

        assertThatThrownBy(() -> org.addMember(user, Role.SUPER_ADMIN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> org.addMember(user, Role.CUSTOMER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void khong_them_trung_thanh_vien() {
        Organization org = newOrg();
        UserId user = UserId.of(UUID.randomUUID());
        org.addMember(user, Role.ORG_OWNER, NOW);

        assertThatThrownBy(() -> org.addMember(user, Role.EVENT_MANAGER, NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
