// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.payout.application.command.ApprovePayoutHandler;
import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutStatus;
import com.nexaticket.payout.domain.port.PayoutRepository;
import com.nexaticket.platform.test.PostgresSingleton;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Vòng đời lô chi trả đi qua PostgreSQL thật: thứ được ghi xuống và đọc lại, và tiền nào còn tính là
 * "đang trên đường chuyển".
 *
 * <p>{@link FourEyesIT} đã phủ CHECK bốn mắt và tổng của các lô CHỜ DUYỆT. Ở đây là phần chỉ câu SQL
 * trả lời được: lô đã hoàn tất hoặc bị từ chối KHÔNG còn chiếm số dư, và các cột kiểm toán (người
 * duyệt, mã giao dịch, lý do từ chối) sống sót qua một lần ghi rồi đọc lại.
 *
 * <p>Mọi fixture dùng UUID mới mỗi lần chạy: container được dùng lại giữa các lần chạy nên dữ liệu
 * cũ vẫn còn đó.
 */
@SpringBootTest
@ActiveProfiles("test")
class PayoutRepositoryIT {

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
    @DisplayName("Tiền đang chuyển chỉ gồm lô chờ duyệt và đã duyệt — lô hoàn tất hay bị từ chối thì không")
    void tien_dang_chuyen_bo_lo_da_ket_thuc() {
        UUID organizationId = UUID.randomUUID();
        UUID accountId = persistAccount(organizationId);
        persistBatch(accountId, organizationId, 10_000_000L);
        PayoutBatch approved = persistBatch(accountId, organizationId, 20_000_000L);
        PayoutBatch completed = persistBatch(accountId, organizationId, 40_000_000L);
        PayoutBatch rejected = persistBatch(accountId, organizationId, 80_000_000L);

        approvePayout.approve(approved.id(), UUID.randomUUID());
        approvePayout.approve(completed.id(), UUID.randomUUID());
        approvePayout.markCompleted(
                completed.id(), "FT" + UUID.randomUUID().toString().substring(0, 8));
        approvePayout.reject(rejected.id(), "Sai số tài khoản");

        assertThat(payouts.inTransitVnd(organizationId)).isEqualTo(30_000_000L);
    }

    @Test
    @DisplayName("Tổ chức chưa có lô nào: tiền đang chuyển là 0, không phải null")
    void chua_co_lo_nao() {
        assertThat(payouts.inTransitVnd(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("Duyệt rồi đọc lại: giữ người duyệt và thời điểm duyệt; xác nhận ghi mã giao dịch và mốc hoàn tất")
    void duyet_roi_doc_lai() {
        UUID organizationId = UUID.randomUUID();
        PayoutBatch batch = persistBatch(persistAccount(organizationId), organizationId, 5_000_000L);
        UUID approver = UUID.randomUUID();

        approvePayout.approve(batch.id(), approver);
        PayoutBatch reloaded = payouts.lockById(batch.id()).orElseThrow();

        assertThat(reloaded.status()).isEqualTo(PayoutStatus.APPROVED);
        assertThat(reloaded.approvedBy()).isEqualTo(approver);
        assertThat(reloaded.approvedAt()).isNotNull();

        String reference = "FT" + UUID.randomUUID().toString().substring(0, 10);
        approvePayout.markCompleted(batch.id(), reference);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, approved_by, bank_reference, completed_at FROM payout_batches WHERE id = ?",
                batch.id());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("approved_by")).isEqualTo(approver);
        assertThat(row.get("bank_reference")).isEqualTo(reference);
        assertThat(row.get("completed_at")).isNotNull();
    }

    @Test
    @DisplayName("Từ chối: lý do được ghi xuống, không có người duyệt")
    void tu_choi_ghi_ly_do() {
        UUID organizationId = UUID.randomUUID();
        PayoutBatch batch = persistBatch(persistAccount(organizationId), organizationId, 5_000_000L);

        approvePayout.reject(batch.id(), "Tên chủ tài khoản không khớp");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, rejected_reason, approved_by FROM payout_batches WHERE id = ?", batch.id());
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("rejected_reason")).isEqualTo("Tên chủ tài khoản không khớp");
        assertThat(row.get("approved_by")).isNull();
    }

    private UUID persistAccount(UUID organizationId) {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO payout_accounts (id, organization_id, bank_bin, bank_name, account_number,
                                             account_holder, created_by)
                VALUES (?, ?, '970422', 'MB Bank', '0123456789', 'CONG TY TNHH ABC', ?)
                """,
                accountId,
                organizationId,
                UUID.randomUUID());
        return accountId;
    }

    private PayoutBatch persistBatch(UUID accountId, UUID organizationId, long amountVnd) {
        var batch = PayoutBatch.create(organizationId, accountId, amountVnd, UUID.randomUUID());
        payouts.save(batch, "970422", "0123456789", "CONG TY TNHH ABC");
        return batch;
    }
}
