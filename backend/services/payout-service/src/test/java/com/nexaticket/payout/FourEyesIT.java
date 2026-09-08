// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payout.application.command.ApprovePayoutHandler;
import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutStatus;
import com.nexaticket.payout.domain.port.PayoutRepository;
import com.nexaticket.platform.test.PostgresSingleton;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nguyên tắc bốn mắt, kiểm với PostgreSQL thật.
 *
 * <p>{@link com.nexaticket.payout.domain.PayoutBatchTest} đã phủ luật ở tầng domain. Ở đây kiểm
 * thứ <b>chỉ database mới chứng minh được</b>: CHECK constraint chặn cả những đường không đi qua
 * code Java — một script sửa dữ liệu chạy vội lúc 2 giờ sáng chẳng hạn. Chi trả là chỗ mà "đường
 * thường" không phải mối lo duy nhất.
 */
@SpringBootTest
@ActiveProfiles("test")
class FourEyesIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("payout_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @Autowired
    PayoutRepository payouts;

    @Autowired
    ApprovePayoutHandler approvePayout;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Tự duyệt lô của mình bị từ chối với mã lỗi rõ ràng")
    void tu_duyet_bi_tu_choi() {
        UUID creator = UUID.randomUUID();
        var batch = persistBatch(creator);

        assertThat(codeOf(() -> approvePayout.approve(batch.id(), creator))).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(statusOf(batch.id())).isEqualTo(PayoutStatus.PENDING_APPROVAL.name());
    }

    @Test
    @DisplayName("Người khác duyệt được, rồi xác nhận đã chuyển khoản")
    void duong_hoan_chinh() {
        var batch = persistBatch(UUID.randomUUID());

        approvePayout.approve(batch.id(), UUID.randomUUID());
        approvePayout.markCompleted(batch.id(), "FT26090812345");

        assertThat(statusOf(batch.id())).isEqualTo(PayoutStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("Ghi thẳng SQL để tự duyệt: CHECK constraint của database từ chối")
    void constraint_chan_ca_duong_sql() {
        // Cố tình đi vòng qua domain — đúng như một script sửa dữ liệu sẽ làm.
        UUID creator = UUID.randomUUID();
        var batch = persistBatch(creator);

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payout_batches SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?",
                        creator,
                        batch.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Tiền đang trên đường chuyển được trừ khỏi số dư khả dụng")
    void tien_dang_chuyen_bi_tru() {
        // Hai lô cùng dựa trên một số dư sẽ chi vượt số dư mà không cổng nào bắt được, nếu
        // phần đang trên đường chuyển không bị trừ ra.
        UUID organizationId = UUID.randomUUID();
        UUID accountId = persistAccount(organizationId, UUID.randomUUID());
        persistBatchOn(accountId, organizationId, 40_000_000L, UUID.randomUUID());
        persistBatchOn(accountId, organizationId, 25_000_000L, UUID.randomUUID());

        assertThat(payouts.inTransitVnd(organizationId)).isEqualTo(65_000_000L);
    }

    private PayoutBatch persistBatch(UUID creator) {
        UUID organizationId = UUID.randomUUID();
        return persistBatchOn(persistAccount(organizationId, creator), organizationId, 100_000_000L, creator);
    }

    /**
     * Một tổ chức chỉ có MỘT đích chi trả đang hoạt động — uq_single_active_payout_account ép
     * điều đó. Vì vậy nhiều lô của cùng một tổ chức phải dùng lại cùng một tài khoản.
     */
    private UUID persistAccount(UUID organizationId, UUID creator) {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO payout_accounts (id, organization_id, bank_bin, bank_name, account_number,
                                             account_holder, created_by)
                VALUES (?, ?, '970422', 'MB Bank', '0123456789', 'CONG TY TNHH ABC', ?)
                """,
                accountId,
                organizationId,
                creator);
        return accountId;
    }

    private PayoutBatch persistBatchOn(UUID accountId, UUID organizationId, long amountVnd, UUID creator) {
        var batch = PayoutBatch.create(organizationId, accountId, amountVnd, creator);
        payouts.save(batch, "970422", "0123456789", "CONG TY TNHH ABC");
        return batch;
    }

    private String statusOf(UUID batchId) {
        return jdbc.queryForObject("SELECT status FROM payout_batches WHERE id = ?", String.class, batchId);
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException e) {
            return e.errorCode().code();
        }
    }
}
