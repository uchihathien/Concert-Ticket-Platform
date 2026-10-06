// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain.model;

import com.nexaticket.kernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Một dòng Nợ hoặc Có trên một tài khoản.
 *
 * <p>Bất biến: sau khi ghi thì không bao giờ sửa. Sửa sai bằng bút toán đảo.
 */
public record Posting(UUID id, UUID accountId, Direction direction, Money amount, int lineNo) {

    public Posting {
        Objects.requireNonNull(id, "id không được null");
        Objects.requireNonNull(accountId, "accountId không được null");
        Objects.requireNonNull(direction, "direction không được null");
        Objects.requireNonNull(amount, "amount không được null");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Định khoản 0 đồng không có ý nghĩa kế toán");
        }
        if (lineNo < 1) {
            throw new IllegalArgumentException("lineNo bắt đầu từ 1, nhận được: " + lineNo);
        }
    }

    public static Posting debit(UUID accountId, Money amount, int lineNo) {
        return new Posting(UUID.randomUUID(), accountId, Direction.DEBIT, amount, lineNo);
    }

    public static Posting credit(UUID accountId, Money amount, int lineNo) {
        return new Posting(UUID.randomUUID(), accountId, Direction.CREDIT, amount, lineNo);
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }

    /** Dòng đảo chiều, dùng khi lập bút toán đảo. */
    public Posting reversed(int newLineNo) {
        return new Posting(UUID.randomUUID(), accountId, direction.opposite(), amount, newLineNo);
    }
}
