// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hai lệnh cùng sửa thành viên của một tổ chức, chạy chồng lên nhau trên PostgreSQL thật.
 *
 * <h2>Vì sao cần</h2>
 *
 * <p>{@code JdbcOrganizationRepository.save} đồng bộ cả tập thành viên theo ảnh chụp lúc đọc: xoá
 * mọi dòng KHÔNG có trong ảnh, rồi upsert lại mọi dòng có trong ảnh. {@code findById} không khoá
 * gì và aggregate không có version. Hai lệnh đọc cùng lúc thì lệnh ghi sau thắng toàn bộ:
 *
 * <ul>
 *   <li>một người vừa nhận lời mời (lời mời đã bị đánh dấu dùng) bị lệnh gỡ người khác xoá mất
 *       membership — và không nhận lại được, vì lời mời đã dùng;
 *   <li>một người vừa bị gỡ được lệnh thêm người khác chèn lại — "gỡ thành viên" âm thầm vô hiệu.
 * </ul>
 *
 * <p>Test không ép một cách sửa cụ thể: khoá bi quan ({@code SELECT ... FOR UPDATE}) làm lệnh thứ
 * hai chờ — ca test chờ có giới hạn rồi đi tiếp; khoá lạc quan làm lệnh ghi sau thất bại — ca test
 * chấp nhận điều đó. Thứ không được chấp nhận là cả hai cùng "thành công" mà một trong hai mất dấu.
 *
 * <p>Dữ liệu duy nhất theo từng lần chạy (container được dùng lại giữa các lần chạy).
 */
class OrganizationConcurrencyIT extends PostgresTestBase {

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    UserRepository users;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UserId newUser(String prefix) {
        return users.upsertByIdpSubject(unique("sub"), unique(prefix) + "@example.com", prefix)
                .id();
    }

    /**
     * Lệnh "trước" đọc, rồi lệnh "sau" chạy trọn vẹn (đọc–sửa–ghi–commit), rồi lệnh "trước" mới sửa
     * và ghi trên ảnh chụp cũ của nó.
     *
     * @return lệnh nào commit được; lệnh bị từ chối (xung đột, hay vi phạm bất biến khi đọc lại) là
     *     false
     */
    private Outcome interleave(TenantId id, Consumer<Organization> first, Consumer<Organization> second)
            throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstLoaded = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        Future<Boolean> firstRun = pool.submit(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    Organization org = organizations.findById(id).orElseThrow();
                    firstLoaded.countDown();
                    try {
                        // Có khoá bi quan thì lệnh sau đang chờ chính giao dịch này: chờ có giới hạn
                        // rồi đi tiếp, để test không treo.
                        secondDone.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    first.accept(org);
                    organizations.save(org);
                });
                return true;
            } catch (RuntimeException conflict) {
                return false;
            }
        });

        Future<Boolean> secondRun = pool.submit(() -> {
            try {
                firstLoaded.await(5, TimeUnit.SECONDS);
                tx.executeWithoutResult(status -> {
                    Organization org = organizations.findById(id).orElseThrow();
                    second.accept(org);
                    organizations.save(org);
                });
                return true;
            } catch (RuntimeException conflict) {
                return false;
            } finally {
                secondDone.countDown();
            }
        });

        boolean secondCommitted = secondRun.get(15, TimeUnit.SECONDS);
        return new Outcome(firstRun.get(15, TimeUnit.SECONDS), secondCommitted);
    }

    private record Outcome(boolean first, boolean second) {}

    @Test
    @DisplayName("gỡ một người không được xoá mất người khác vừa nhận lời mời cùng lúc")
    void go_thanh_vien_khong_xoa_nguoi_vua_vao() throws Exception {
        UserId owner = newUser("owner");
        UserId leaving = newUser("leaving");
        UserId joining = newUser("joining");
        Organization org = Organization.create(new Slug(unique("dong-thoi")), "Đồng thời", Instant.now());
        org.addMember(owner, Role.ORG_OWNER, Instant.now());
        org.addMember(leaving, Role.EVENT_MANAGER, Instant.now());
        organizations.save(org);

        Outcome outcome = interleave(
                org.id(),
                stale -> stale.removeMember(leaving),
                fresh -> fresh.addMember(joining, Role.CHECKIN_STAFF, Instant.now()));

        Organization after = organizations.findById(org.id()).orElseThrow();
        if (outcome.second()) {
            assertThat(after.findMember(joining))
                    .as("người vừa nhận lời mời (lệnh đã commit) phải còn là thành viên")
                    .isPresent();
        }
        if (outcome.first()) {
            assertThat(after.findMember(leaving)).isEmpty();
        }
        assertThat(outcome.first() || outcome.second()).isTrue();
    }

    @Test
    @DisplayName("thêm một người không được hồi sinh người vừa bị gỡ cùng lúc")
    void them_thanh_vien_khong_hoi_sinh_nguoi_bi_go() throws Exception {
        UserId owner = newUser("owner");
        UserId removed = newUser("removed");
        UserId joining = newUser("joining");
        Organization org = Organization.create(new Slug(unique("hoi-sinh")), "Hồi sinh", Instant.now());
        org.addMember(owner, Role.ORG_OWNER, Instant.now());
        org.addMember(removed, Role.ORG_ADMIN, Instant.now());
        organizations.save(org);

        Outcome outcome = interleave(
                org.id(),
                stale -> stale.addMember(joining, Role.EVENT_MANAGER, Instant.now()),
                fresh -> fresh.removeMember(removed));

        Organization after = organizations.findById(org.id()).orElseThrow();
        if (outcome.second()) {
            assertThat(after.findMember(removed))
                    .as("người đã bị gỡ (lệnh đã commit) không được quay lại vì một lệnh khác ghi đè")
                    .isEmpty();
        }
        if (outcome.first()) {
            assertThat(after.findMember(joining)).isPresent();
        }
        assertThat(outcome.first() || outcome.second()).isTrue();
    }

    @Test
    @DisplayName("hai lệnh gỡ hai chủ sở hữu khác nhau cùng lúc: một lệnh phải bị từ chối, không lệnh nào bị nuốt")
    void hai_lenh_go_chu_so_huu_dong_thoi() throws Exception {
        UserId ownerA = newUser("owner-a");
        UserId ownerB = newUser("owner-b");
        Organization org = Organization.create(new Slug(unique("hai-chu")), "Hai chủ", Instant.now());
        org.addMember(ownerA, Role.ORG_OWNER, Instant.now());
        org.addMember(ownerB, Role.ORG_OWNER, Instant.now());
        organizations.save(org);

        Outcome outcome =
                interleave(org.id(), stale -> stale.removeMember(ownerA), fresh -> fresh.removeMember(ownerB));

        Organization after = organizations.findById(org.id()).orElseThrow();
        assertThat(after.members())
                .as("bất biến: luôn còn ít nhất một ORG_OWNER")
                .anyMatch(m -> m.role() == Role.ORG_OWNER);
        // Lệnh nào báo thành công thì người nó gỡ phải thật sự biến mất. Ghi đè bằng ảnh chụp cũ sẽ
        // chèn lại người mà lệnh kia vừa gỡ — cả hai quản trị viên đều tưởng đã xong việc.
        if (outcome.first()) {
            assertThat(after.findMember(ownerA)).as("lệnh gỡ A đã commit").isEmpty();
        }
        if (outcome.second()) {
            assertThat(after.findMember(ownerB)).as("lệnh gỡ B đã commit").isEmpty();
        }
    }
}
