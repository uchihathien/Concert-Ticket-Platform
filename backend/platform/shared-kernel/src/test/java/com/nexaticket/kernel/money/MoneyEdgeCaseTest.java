// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Biên và tràn số của {@link Money}, bổ sung cho {@code MoneyTest}.
 *
 * <p>Tiền là chỗ lỗi làm tròn hay tràn số im lặng gây hại nhất: mọi phép tính phải hoặc đúng, hoặc
 * ném lỗi — không bao giờ cho một con số sai.
 */
class MoneyEdgeCaseTest {

    @Nested
    @DisplayName("percentOf (basis point, làm tròn xuống)")
    class PercentOf {

        @ParameterizedTest(name = "{0} đ x {1} bps = {2} đ")
        @CsvSource({
            "0, 500, 0",
            "1, 500, 0",
            "19, 500, 0",
            "20, 500, 1",
            "999, 500, 49",
            "1000000, 0, 0",
            "1000000, 10000, 1000000",
            "1000000, 1, 100",
            "9999, 1, 0",
            "10000, 1, 1",
            "123456789, 1234, 15234567"
        })
        @DisplayName("làm tròn xuống theo từng ca")
        void lam_tron_xuong(long amount, int bps, long expected) {
            assertThat(Money.ofVnd(amount).percentOf(bps)).isEqualTo(Money.ofVnd(expected));
        }

        @Test
        @DisplayName("0 và 10000 là hai biên hợp lệ; -1 và 10001 bị từ chối")
        void bien_basis_point() {
            Money m = Money.ofVnd(1000);
            assertThat(m.percentOf(0)).isEqualTo(Money.ZERO);
            assertThat(m.percentOf(10_000)).isEqualTo(m);
            assertThatThrownBy(() -> m.percentOf(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> m.percentOf(10_001)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("hoa hồng + phần còn lại luôn bằng đúng tổng — không rơi mất một đồng")
        void khong_mat_dong_nao() {
            Money gross = Money.ofVnd(1_234_567);
            Money fee = gross.percentOf(750);

            assertThat(fee.plus(gross.minus(fee))).isEqualTo(gross);
        }

        @Test
        @DisplayName("số tiền rất lớn nhân basis point tràn long: ném ArithmeticException, không cho kết quả sai")
        void tran_so_khi_nhan_bps() {
            Money huge = Money.ofVnd(Long.MAX_VALUE / 5000);

            assertThatThrownBy(() -> huge.percentOf(10_000)).isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("Số học")
    class Arithmetic {

        @Test
        @DisplayName("plus và minus về đúng giá trị; minus ra đúng 0 thì hợp lệ")
        void plus_minus() {
            assertThat(Money.ofVnd(700).plus(Money.ofVnd(300))).isEqualTo(Money.ofVnd(1000));
            assertThat(Money.ofVnd(1000).minus(Money.ofVnd(1000)))
                    .isEqualTo(Money.ZERO)
                    .matches(Money::isZero);
        }

        @Test
        @DisplayName("plus đúng tới Long.MAX_VALUE được; thêm 1 đồng thì tràn")
        void plus_bien_long() {
            Money almost = Money.ofVnd(Long.MAX_VALUE - 1);

            assertThat(almost.plus(Money.ofVnd(1)).amountVnd()).isEqualTo(Long.MAX_VALUE);
            assertThatThrownBy(() -> almost.plus(Money.ofVnd(2))).isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("times: hệ số 0 ra 0, hệ số âm bị từ chối, tràn bị chặn")
        void times() {
            assertThat(Money.ofVnd(250_000).times(4)).isEqualTo(Money.ofVnd(1_000_000));
            assertThat(Money.ofVnd(250_000).times(0)).isEqualTo(Money.ZERO);
            assertThatThrownBy(() -> Money.ofVnd(1).times(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.ofVnd(Long.MAX_VALUE).times(2)).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> Money.ofVnd(Long.MAX_VALUE / 2 + 1).times(Integer.MAX_VALUE))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("trừ quá số dư: thông điệp nêu cả hai số")
        void tru_qua_so_du_thong_diep() {
            assertThatThrownBy(() -> Money.ofVnd(5).minus(Money.ofVnd(6)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("5")
                    .hasMessageContaining("6");
        }

        @Test
        @DisplayName("Long.MIN_VALUE và mọi số âm bị từ chối ngay khi tạo")
        void so_am() {
            assertThatThrownBy(() -> Money.ofVnd(Long.MIN_VALUE)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Money(-100)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("So sánh, bằng nhau, hiển thị")
    class Comparison {

        @Test
        @DisplayName("compareTo, isGreaterThan, equals theo giá trị")
        void so_sanh() {
            Money small = Money.ofVnd(100);
            Money big = Money.ofVnd(200);

            assertThat(small.compareTo(big)).isNegative();
            assertThat(big.compareTo(small)).isPositive();
            assertThat(small.compareTo(Money.ofVnd(100))).isZero();
            assertThat(big.isGreaterThan(small)).isTrue();
            assertThat(small.isGreaterThan(small)).isFalse();
            assertThat(small).isEqualTo(Money.ofVnd(100)).hasSameHashCodeAs(Money.ofVnd(100));
        }

        @Test
        @DisplayName("compareTo(null) ném NPE chứ không coi null là nhỏ nhất")
        void compare_null() {
            assertThatThrownBy(() -> Money.ZERO.compareTo(null)).isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest(name = "{0} -> \"{1}\"")
        @CsvSource({
            "0, '0 ₫'",
            "7, '7 ₫'",
            "999, '999 ₫'",
            "1000, '1.000 ₫'",
            "12345, '12.345 ₫'",
            "100000, '100.000 ₫'",
            "1234567, '1.234.567 ₫'",
            "9223372036854775807, '9.223.372.036.854.775.807 ₫'"
        })
        @DisplayName("format: dấu chấm ngăn cách hàng nghìn, kèm ký hiệu ₫")
        void format(long amount, String expected) {
            assertThat(Money.ofVnd(amount).format()).isEqualTo(expected);
            assertThat(Money.ofVnd(amount)).hasToString(expected);
        }
    }
}
