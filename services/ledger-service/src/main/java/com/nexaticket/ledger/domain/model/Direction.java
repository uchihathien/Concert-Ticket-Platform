// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain.model;

/**
 * Chiều của một định khoản.
 *
 * <p>Sổ cái không có tiền âm: chiều tiền biểu diễn bằng enum này, không bằng dấu của số.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /**
     * Dấu của định khoản khi cộng vào số dư của một tài khoản.
     *
     * <p>Tài khoản số dư thường bên Nợ (tài sản, chi phí) tăng khi ghi Nợ; tài khoản số dư thường
     * bên Có (nợ phải trả, doanh thu) tăng khi ghi Có.
     */
    public int signFor(Direction normalBalance) {
        return this == normalBalance ? 1 : -1;
    }
}
