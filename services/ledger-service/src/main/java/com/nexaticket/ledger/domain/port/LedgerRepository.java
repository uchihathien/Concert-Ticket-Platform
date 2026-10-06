// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain.port;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.model.JournalEntry;
import java.util.Optional;
import java.util.UUID;

/** Cổng ra persistence của sổ cái. */
public interface LedgerRepository {

    /** Tài khoản cấp nền tảng, ví dụ tiền ký quỹ hay doanh thu hoa hồng. */
    UUID platformAccountId(AccountCode code);

    /**
     * Tài khoản của một tổ chức, tạo nếu chưa có.
     *
     * <p>Bộ 2011/2012/2013 được tạo khi nhận {@code OrganizationCreated}; hàm này là lưới an toàn
     * cho trường hợp event tới sau bút toán đầu tiên.
     */
    UUID organizationAccountId(AccountCode code, UUID organizationId);

    /**
     * Ghi bút toán.
     *
     * @throws DuplicateEntryException nếu {@code idempotencyKey} đã tồn tại
     */
    void append(JournalEntry entry);

    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    /** Số dư hiện tại, tính theo chiều số dư thường của tài khoản. */
    Money balanceOf(UUID accountId);

    /** Tổng Nợ và tổng Có toàn hệ thống — dùng cho bảng cân đối thử. */
    TrialBalance trialBalance();

    record TrialBalance(Money totalDebit, Money totalCredit) {
        public boolean isBalanced() {
            return totalDebit.equals(totalCredit);
        }
    }

    /** Ném khi ghi lại một bút toán đã tồn tại — consumer retry là chuyện bình thường. */
    class DuplicateEntryException extends RuntimeException {
        public DuplicateEntryException(String idempotencyKey) {
            super("Bút toán đã tồn tại với idempotencyKey: " + idempotencyKey);
        }
    }
}
