// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import com.nexaticket.ledger.support.LedgerTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spike sổ cái (plan/backend.md §5).
 *
 * <p>Mục đích của bộ test này không phải là kiểm tra code Java, mà là <b>chứng minh database tự bảo
 * vệ được sổ sách</b>. Mọi test ở đây đều cố tình đi vòng qua domain model và ghi thẳng SQL — đúng
 * như một con bug trong application sẽ làm.
 */
class LedgerInvariantIT extends LedgerTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    TransactionTemplate tx;

    private UUID insertEntry(String idempotencyKey) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO journal_entries (id, entry_type, occurred_at, source_type, source_id,
                                             idempotency_key, created_by)
                VALUES (?, 'TEST', now(), 'ADJUSTMENT', ?, ?, 'test')
                """,
                entryId,
                UUID.randomUUID(),
                idempotencyKey);
        return entryId;
    }

    private void insertPosting(UUID entryId, UUID accountId, String direction, long amount, int lineNo) {
        jdbc.update(
                """
                INSERT INTO postings (id, entry_id, account_id, direction, amount_vnd, line_no)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                entryId,
                accountId,
                direction,
                amount,
                lineNo);
    }

    @Test
    @DisplayName("Database từ chối bút toán lệch, kể cả khi application ghi thẳng SQL")
    void but_toan_lech_khong_the_vao_duoc_database() {
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);
        UUID revenue = ledger.platformAccountId(AccountCode.COMMISSION_REVENUE);

        // Nợ 1.000.000 nhưng chỉ ghi Có 900.000 — lệch 100.000.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID entryId = insertEntry("lech-" + UUID.randomUUID());
                    insertPosting(entryId, cash, "DEBIT", 1_000_000L, 1);
                    insertPosting(entryId, revenue, "CREDIT", 900_000L, 2);
                }))
                .as("constraint trigger phải chặn lúc COMMIT")
                .hasMessageContaining("khong can");

        assertThat(ledger.trialBalance().isBalanced())
                .as("sổ cái vẫn cân sau khi bút toán lệch bị từ chối")
                .isTrue();
    }

    @Test
    @DisplayName("Bút toán cân thì ghi được")
    void but_toan_can_ghi_duoc() {
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);
        UUID suspense = ledger.platformAccountId(AccountCode.SUSPENSE);

        tx.executeWithoutResult(status -> {
            UUID entryId = insertEntry("can-" + UUID.randomUUID());
            insertPosting(entryId, cash, "DEBIT", 500_000L, 1);
            insertPosting(entryId, suspense, "CREDIT", 500_000L, 2);
        });

        assertThat(ledger.trialBalance().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("Định khoản 0 đồng và số âm bị CHECK chặn")
    void so_tien_phai_duong() {
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID entryId = insertEntry("am-" + UUID.randomUUID());
                    insertPosting(entryId, cash, "DEBIT", -100L, 1);
                }))
                .hasMessageContaining("ck_amount_positive");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID entryId = insertEntry("khong-" + UUID.randomUUID());
                    insertPosting(entryId, cash, "DEBIT", 0L, 1);
                }))
                .hasMessageContaining("ck_amount_positive");
    }

    @Test
    @DisplayName("Bút toán một dòng cũng bị chặn — không có gì để đối ứng")
    void mot_dong_khong_the_can() {
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID entryId = insertEntry("mot-dong-" + UUID.randomUUID());
                    insertPosting(entryId, cash, "DEBIT", 100_000L, 1);
                }))
                .hasMessageContaining("khong can");
    }

    @Test
    @DisplayName("Trùng idempotency_key bị chặn — consumer retry không ghi sổ hai lần")
    void idempotency_key_la_duy_nhat() {
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);
        UUID suspense = ledger.platformAccountId(AccountCode.SUSPENSE);
        String key = "trung-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            UUID entryId = insertEntry(key);
            insertPosting(entryId, cash, "DEBIT", 200_000L, 1);
            insertPosting(entryId, suspense, "CREDIT", 200_000L, 2);
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertEntry(key)))
                .hasMessageContaining("idempotency_key");
    }

    @Test
    @DisplayName("Tài khoản nền tảng chỉ tồn tại một bản cho mỗi mã")
    void tai_khoan_nen_tang_khong_trung() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, owner_type)
                        VALUES (?, '1010', 'Trung', 'ASSET', 'DEBIT', 'PLATFORM')
                        """,
                        UUID.randomUUID()))
                .hasMessageContaining("uq_account_platform");
    }

    @Test
    @DisplayName("Tài khoản của tổ chức bắt buộc có owner_id")
    void tai_khoan_to_chuc_phai_co_chu() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, owner_type)
                        VALUES (?, '2011', 'Thieu chu', 'LIABILITY', 'CREDIT', 'ORGANIZATION')
                        """,
                        UUID.randomUUID()))
                .hasMessageContaining("ck_owner_id");
    }

    @Test
    @DisplayName("Số dư tính đúng theo chiều số dư thường của tài khoản")
    void so_du_theo_chieu_dung() {
        UUID org = UUID.randomUUID();
        UUID payableHeld = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);

        Money before = ledger.balanceOf(cash);

        tx.executeWithoutResult(status -> {
            UUID entryId = insertEntry("sodu-" + UUID.randomUUID());
            insertPosting(entryId, cash, "DEBIT", 1_000_000L, 1);
            insertPosting(entryId, payableHeld, "CREDIT", 1_000_000L, 2);
        });

        // 1010 là tài sản, số dư thường bên Nợ -> ghi Nợ thì tăng.
        assertThat(ledger.balanceOf(cash)).isEqualTo(before.plus(Money.ofVnd(1_000_000)));
        // 2011 là nợ phải trả, số dư thường bên Có -> ghi Có thì tăng.
        assertThat(ledger.balanceOf(payableHeld)).isEqualTo(Money.ofVnd(1_000_000));
    }
}
