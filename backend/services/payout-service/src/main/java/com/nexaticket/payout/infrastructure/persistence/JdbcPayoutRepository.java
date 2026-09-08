// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.infrastructure.persistence;

import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutStatus;
import com.nexaticket.payout.domain.port.PayoutRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPayoutRepository implements PayoutRepository {

    private final JdbcTemplate jdbc;

    public JdbcPayoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PayoutBatch batch, String bankBin, String accountNumber, String accountHolder) {
        jdbc.update(
                """
                INSERT INTO payout_batches (id, organization_id, payout_account_id, amount_vnd,
                                            bank_bin, account_number, account_holder, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batch.id(),
                batch.organizationId(),
                batch.payoutAccountId(),
                batch.amountVnd(),
                bankBin,
                accountNumber,
                accountHolder,
                batch.status().name(),
                batch.createdBy());
    }

    @Override
    public Optional<PayoutBatch> lockById(UUID batchId) {
        // FOR UPDATE: hai superadmin cùng bấm duyệt một lô trong cùng một giây là chuyện có thật.
        return jdbc
                .query(
                        """
                        SELECT id, organization_id, payout_account_id, amount_vnd, created_by,
                               status, approved_by, approved_at
                          FROM payout_batches WHERE id = ? FOR UPDATE
                        """,
                        JdbcPayoutRepository::map,
                        batchId)
                .stream()
                .findFirst();
    }

    @Override
    public void update(PayoutBatch batch, Instant at) {
        jdbc.update(
                """
                UPDATE payout_batches
                   SET status = ?, approved_by = ?, approved_at = ?,
                       bank_reference = ?, completed_at = ?, rejected_reason = ?
                 WHERE id = ?
                """,
                batch.status().name(),
                batch.approvedBy(),
                batch.approvedAt() == null ? null : Timestamp.from(batch.approvedAt()),
                batch.bankReference(),
                batch.completedAt() == null ? null : Timestamp.from(batch.completedAt()),
                batch.rejectedReason(),
                batch.id());
    }

    @Override
    public List<PayoutBatch> pendingApproval(int limit) {
        return jdbc.query(
                """
                SELECT id, organization_id, payout_account_id, amount_vnd, created_by,
                       status, approved_by, approved_at
                  FROM payout_batches WHERE status = 'PENDING_APPROVAL'
                 ORDER BY created_at LIMIT ?
                """,
                JdbcPayoutRepository::map,
                limit);
    }

    @Override
    public Optional<PayoutAccount> activeAccountOf(UUID organizationId) {
        return jdbc
                .query(
                        """
                        SELECT id, organization_id, bank_bin, bank_name, account_number, account_holder, is_active
                          FROM payout_accounts WHERE organization_id = ? AND is_active
                        """,
                        (rs, i) -> new PayoutAccount(
                                rs.getObject("id", UUID.class),
                                rs.getObject("organization_id", UUID.class),
                                rs.getString("bank_bin"),
                                rs.getString("bank_name"),
                                rs.getString("account_number"),
                                rs.getString("account_holder"),
                                rs.getBoolean("is_active")),
                        organizationId)
                .stream()
                .findFirst();
    }

    @Override
    public void saveAccount(PayoutAccount account) {
        jdbc.update(
                """
                INSERT INTO payout_accounts (id, organization_id, bank_bin, bank_name, account_number,
                                             account_holder, is_active, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                account.id(),
                account.organizationId(),
                account.bankBin(),
                account.bankName(),
                account.accountNumber(),
                account.accountHolder(),
                account.active(),
                account.organizationId());
    }

    @Override
    public long inTransitVnd(UUID organizationId) {
        Long total = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount_vnd), 0) FROM payout_batches
                 WHERE organization_id = ? AND status IN ('PENDING_APPROVAL', 'APPROVED')
                """,
                Long.class,
                organizationId);
        return total == null ? 0L : total;
    }

    private static PayoutBatch map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return PayoutBatch.rehydrate(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("payout_account_id", UUID.class),
                rs.getLong("amount_vnd"),
                rs.getObject("created_by", UUID.class),
                PayoutStatus.valueOf(rs.getString("status")),
                rs.getObject("approved_by", UUID.class),
                approvedAt == null ? null : approvedAt.toInstant());
    }
}
