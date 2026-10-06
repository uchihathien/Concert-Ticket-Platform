// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.infrastructure.persistence;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.model.Direction;
import com.nexaticket.ledger.domain.model.JournalEntry;
import com.nexaticket.ledger.domain.model.Posting;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter persistence của sổ cái.
 *
 * <p>Không có phương thức nào cập nhật hay xoá: sổ cái chỉ ghi thêm (ADR-1005). Quyền database cũng
 * đã {@code REVOKE UPDATE, DELETE} nên kể cả viết nhầm cũng không chạy được.
 */
@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcTemplate jdbc;

    public JdbcLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID platformAccountId(AccountCode code) {
        List<UUID> found = jdbc.query(
                "SELECT id FROM ledger_accounts WHERE code = ? AND owner_type = 'PLATFORM'",
                (rs, i) -> rs.getObject("id", UUID.class),
                code.value());
        if (found.isEmpty()) {
            throw new IllegalStateException("Thiếu tài khoản nền tảng " + code + " — kiểm tra migration seed");
        }
        return found.get(0);
    }

    /**
     * Lấy tài khoản của tổ chức, tạo nếu chưa có.
     *
     * <p>Đường thường là chỉ SELECT — tài khoản được tạo khi nhận {@code OrganizationCreated}, nên
     * hầu hết lời gọi không ghi gì và không giữ khoá nào.
     *
     * <p>Nhánh tạo dùng {@code ON CONFLICT ... DO UPDATE ... RETURNING id} chứ không phải
     * {@code DO NOTHING}: {@code DO NOTHING} không trả về dòng nào khi đụng độ, buộc phải SELECT
     * lại và <b>có thể lặp vô hạn</b> khi nhiều luồng cùng tạo một tài khoản. {@code DO UPDATE}
     * luôn trả về id, dù là dòng vừa chèn hay dòng đã có.
     *
     * <p>Mệnh đề {@code WHERE} trong ON CONFLICT là bắt buộc để Postgres suy ra đúng partial unique
     * index {@code uq_account_owned}.
     */
    @Override
    public UUID organizationAccountId(AccountCode code, UUID organizationId) {
        List<UUID> found = jdbc.query(
                """
                SELECT id FROM ledger_accounts
                 WHERE code = ? AND owner_type = 'ORGANIZATION' AND owner_id = ?
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                code.value(),
                organizationId);
        if (!found.isEmpty()) {
            return found.get(0);
        }

        return jdbc.queryForObject(
                """
                INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, owner_type, owner_id)
                VALUES (?, ?, ?, 'LIABILITY', 'CREDIT', 'ORGANIZATION', ?)
                ON CONFLICT (code, owner_type, owner_id) WHERE owner_type <> 'PLATFORM'
                DO UPDATE SET code = EXCLUDED.code
                RETURNING id
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                UUID.randomUUID(),
                code.value(),
                nameFor(code),
                organizationId);
    }

    private static String nameFor(AccountCode code) {
        if (code.equals(AccountCode.ORGANIZER_PAYABLE_HELD)) {
            return "Phai tra to chuc - dang giu";
        }
        if (code.equals(AccountCode.ORGANIZER_PAYABLE_AVAILABLE)) {
            return "Phai tra to chuc - kha dung";
        }
        if (code.equals(AccountCode.REFUND_RESERVE)) {
            return "Du phong hoan tien";
        }
        if (code.equals(AccountCode.PAYOUT_IN_TRANSIT)) {
            return "Chi tra dang thuc hien";
        }
        return "Tai khoan " + code;
    }

    @Override
    public void append(JournalEntry entry) {
        try {
            jdbc.update(
                    """
                    INSERT INTO journal_entries (id, entry_type, occurred_at, source_type, source_id,
                                                 organization_id, correlation_id, idempotency_key,
                                                 memo, reverses_entry_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entry.id(),
                    entry.entryType(),
                    Timestamp.from(entry.occurredAt()),
                    entry.source().type().name(),
                    entry.source().id(),
                    entry.organizationId(),
                    com.nexaticket.kernel.id.CorrelationContext.current(),
                    entry.idempotencyKey(),
                    entry.memo(),
                    entry.reversesEntryId(),
                    entry.createdBy());
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException(entry.idempotencyKey());
        }

        for (Posting posting : entry.postings()) {
            jdbc.update(
                    """
                    INSERT INTO postings (id, entry_id, account_id, direction, amount_vnd, line_no)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    posting.id(),
                    entry.id(),
                    posting.accountId(),
                    posting.direction().name(),
                    posting.amount().amountVnd(),
                    posting.lineNo());
        }
        // Constraint trigger kiểm tra cân bằng khi COMMIT, không phải ở đây.
    }

    @Override
    public Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM journal_entries WHERE idempotency_key = ?",
                (rs, i) -> rs.getObject("id", UUID.class),
                idempotencyKey);
        return ids.isEmpty() ? Optional.empty() : Optional.of(load(ids.get(0)));
    }

    private JournalEntry load(UUID entryId) {
        return jdbc.queryForObject(
                """
                SELECT id, entry_type, occurred_at, source_type, source_id, organization_id,
                       idempotency_key, memo, reverses_entry_id, created_by
                  FROM journal_entries WHERE id = ?
                """,
                (rs, i) -> {
                    JournalEntry.Builder builder = JournalEntry.builder()
                            .id(rs.getObject("id", UUID.class))
                            .entryType(rs.getString("entry_type"))
                            .occurredAt(rs.getTimestamp("occurred_at").toInstant())
                            .source(new JournalEntry.SourceRef(
                                    JournalEntry.SourceRef.Type.valueOf(rs.getString("source_type")),
                                    rs.getObject("source_id", UUID.class)))
                            .organizationId(rs.getObject("organization_id", UUID.class))
                            .idempotencyKey(rs.getString("idempotency_key"))
                            .memo(rs.getString("memo"))
                            .reversesEntryId(rs.getObject("reverses_entry_id", UUID.class))
                            .createdBy(rs.getString("created_by"));
                    builder.postings(loadPostings(entryId));
                    return builder.build();
                },
                entryId);
    }

    private List<Posting> loadPostings(UUID entryId) {
        return jdbc.query(
                """
                SELECT id, account_id, direction, amount_vnd, line_no
                  FROM postings WHERE entry_id = ? ORDER BY line_no
                """,
                (rs, i) -> new Posting(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        Direction.valueOf(rs.getString("direction")),
                        Money.ofVnd(rs.getLong("amount_vnd")),
                        rs.getInt("line_no")),
                entryId);
    }

    /**
     * Số dư = snapshot gần nhất + các định khoản phát sinh sau đó.
     *
     * <p>Không có snapshot thì cộng từ đầu — đúng với quy mô hiện tại; job snapshot chạy mỗi 5 phút
     * sẽ cắt ngắn phần phải cộng khi dữ liệu lớn lên.
     */
    @Override
    public Money balanceOf(UUID accountId) {
        Long balance = jdbc.queryForObject(
                """
                WITH snap AS (
                    SELECT balance_vnd, as_of_seq
                      FROM account_balance_snapshots
                     WHERE account_id = ?
                     ORDER BY as_of_seq DESC
                     LIMIT 1
                ),
                acct AS (SELECT normal_balance FROM ledger_accounts WHERE id = ?)
                SELECT COALESCE((SELECT balance_vnd FROM snap), 0)
                     + COALESCE(SUM(
                         CASE WHEN p.direction = (SELECT normal_balance FROM acct)
                              THEN p.amount_vnd ELSE -p.amount_vnd END), 0)
                  FROM postings p
                  JOIN journal_entries e ON e.id = p.entry_id
                 WHERE p.account_id = ?
                   AND e.seq > COALESCE((SELECT as_of_seq FROM snap), 0)
                """,
                Long.class,
                accountId,
                accountId,
                accountId);
        return Money.ofVnd(balance == null ? 0L : balance);
    }

    @Override
    public TrialBalance trialBalance() {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount_vnd) FILTER (WHERE direction = 'DEBIT'), 0)  AS d,
                       COALESCE(SUM(amount_vnd) FILTER (WHERE direction = 'CREDIT'), 0) AS c
                  FROM postings
                """,
                (rs, i) -> new TrialBalance(Money.ofVnd(rs.getLong("d")), Money.ofVnd(rs.getLong("c"))));
    }
}
