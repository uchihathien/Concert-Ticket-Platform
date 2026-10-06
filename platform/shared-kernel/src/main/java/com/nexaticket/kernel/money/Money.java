// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.money;

import java.util.Objects;

/**
 * Số tiền VND.
 *
 * <p>Ba quyết định cố ý:
 *
 * <ul>
 *   <li><b>long, không BigDecimal, không double.</b> VND không có phần thập phân.
 *   <li><b>addExact/multiplyExact.</b> Tràn số ném exception thay vì âm thầm cho kết quả sai.
 *   <li><b>Không âm.</b> Chiều tiền biểu diễn bằng Posting.direction ở sổ cái, không bằng số âm.
 * </ul>
 */
public record Money(long amountVnd) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    public Money {
        if (amountVnd < 0) {
            throw new IllegalArgumentException("Số tiền không được âm: " + amountVnd);
        }
    }

    public static Money ofVnd(long amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(amountVnd, other.amountVnd));
    }

    public Money minus(Money other) {
        if (other.amountVnd > amountVnd) {
            throw new IllegalArgumentException("Trừ quá số dư: " + amountVnd + " - " + other.amountVnd);
        }
        return new Money(amountVnd - other.amountVnd);
    }

    public Money times(int factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("Hệ số không được âm: " + factor);
        }
        return new Money(Math.multiplyExact(amountVnd, (long) factor));
    }

    /** Nhân theo basis point (1 bps = 0,01%). Làm tròn xuống. */
    public Money percentOf(int basisPoints) {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("Basis point ngoài khoảng 0..10000: " + basisPoints);
        }
        return new Money(Math.floorDiv(Math.multiplyExact(amountVnd, (long) basisPoints), 10_000L));
    }

    public boolean isZero() {
        return amountVnd == 0L;
    }

    public boolean isGreaterThan(Money other) {
        return amountVnd > other.amountVnd;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(amountVnd, Objects.requireNonNull(other).amountVnd);
    }

    /** Ví dụ: 1.500.000 ₫ */
    public String format() {
        StringBuilder digits = new StringBuilder(Long.toString(amountVnd));
        for (int i = digits.length() - 3; i > 0; i -= 3) {
            digits.insert(i, '.');
        }
        return digits + " \u20AB";
    }

    @Override
    public String toString() {
        return format();
    }
}
