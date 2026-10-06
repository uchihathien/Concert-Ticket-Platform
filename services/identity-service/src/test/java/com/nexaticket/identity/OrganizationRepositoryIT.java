// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import com.nexaticket.kernel.access.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test thật: PostgreSQL qua Testcontainers, Flyway chạy đủ migration của platform +
 * identity.
 */
class OrganizationRepositoryIT extends PostgresTestBase {

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    UserRepository users;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void luu_va_doc_lai_giu_nguyen_thanh_vien() {
        var user = users.upsertByIdpSubject(unique("sub"), unique("a") + "@example.com", "Nguyễn Văn A");
        Organization org = Organization.create(new Slug(unique("nha-hat")), "Nhà hát Lớn", Instant.now());
        org.addMember(user.id(), Role.ORG_OWNER, Instant.now());

        organizations.save(org);
        Organization loaded = organizations.findById(org.id()).orElseThrow();

        assertThat(loaded.name()).isEqualTo("Nhà hát Lớn");
        assertThat(loaded.members()).hasSize(1);
        assertThat(loaded.findMember(user.id()).orElseThrow().role()).isEqualTo(Role.ORG_OWNER);
    }

    @Test
    void xoa_thanh_vien_trong_aggregate_thi_dong_bo_xuong_database() {
        var owner = users.upsertByIdpSubject(unique("sub"), unique("o") + "@example.com", "Chủ");
        var staff = users.upsertByIdpSubject(unique("sub"), unique("s") + "@example.com", "Nhân viên");
        Organization org = Organization.create(new Slug(unique("venue")), "Địa điểm", Instant.now());
        org.addMember(owner.id(), Role.ORG_OWNER, Instant.now());
        org.addMember(staff.id(), Role.CHECKIN_STAFF, Instant.now());
        organizations.save(org);

        Organization loaded = organizations.findById(org.id()).orElseThrow();
        loaded.removeMember(staff.id());
        organizations.save(loaded);

        Organization after = organizations.findById(org.id()).orElseThrow();
        assertThat(after.members()).hasSize(1);
        assertThat(after.findMember(staff.id())).isEmpty();
    }

    @Test
    void findAllByMember_chi_tra_to_chuc_cua_nguoi_do() {
        var alice = users.upsertByIdpSubject(unique("sub"), unique("alice") + "@example.com", "Alice");
        var bob = users.upsertByIdpSubject(unique("sub"), unique("bob") + "@example.com", "Bob");

        Organization orgA = Organization.create(new Slug(unique("org-a")), "Tổ chức A", Instant.now());
        orgA.addMember(alice.id(), Role.ORG_OWNER, Instant.now());
        organizations.save(orgA);

        Organization orgB = Organization.create(new Slug(unique("org-b")), "Tổ chức B", Instant.now());
        orgB.addMember(bob.id(), Role.ORG_OWNER, Instant.now());
        organizations.save(orgB);

        // Đây là hạt nhân của tenant isolation: Alice không bao giờ thấy tổ chức của Bob.
        assertThat(organizations.findAllByMember(alice.id()))
                .extracting(o -> o.id().value())
                .containsExactly(orgA.id().value());
        assertThat(organizations.findAllByMember(bob.id()))
                .extracting(o -> o.id().value())
                .containsExactly(orgB.id().value());
    }

    @Test
    void slugExists_phat_hien_trung() {
        Slug slug = new Slug(unique("trung"));
        assertThat(organizations.slugExists(slug)).isFalse();

        organizations.save(Organization.create(slug, "Tên", Instant.now()));

        assertThat(organizations.slugExists(slug)).isTrue();
    }
}
