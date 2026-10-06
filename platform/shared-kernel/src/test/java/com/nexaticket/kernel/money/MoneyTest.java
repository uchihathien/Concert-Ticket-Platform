// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void khong_cho_so_am() {
        assertThatThrownBy(() -> Money.ofVnd(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tran_so_nem_exception_thay_vi_cho_ket_qua_sai() {
        Money huge = Money.ofVnd(Long.MAX_VALUE);
        assertThatThrownBy(() -> huge.plus(Money.ofVnd(1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void hoa_hong_5_phan_tram_lam_tron_xuong() {
        assertThat(Money.ofVnd(3_000_000).percentOf(500)).isEqualTo(Money.ofVnd(150_000));
        assertThat(Money.ofVnd(999).percentOf(500)).isEqualTo(Money.ofVnd(49));
    }

    @Test
    void format_theo_kieu_viet_nam() {
        assertThat(Money.ofVnd(1_500_000).format()).isEqualTo("1.500.000 \u20AB");
        assertThat(Money.ZERO.format()).isEqualTo("0 \u20AB");
    }

    @Test
    void tru_qua_so_du_bi_chan() {
        assertThatThrownBy(() -> Money.ofVnd(100).minus(Money.ofVnd(101))).isInstanceOf(IllegalArgumentException.class);
    }
}
