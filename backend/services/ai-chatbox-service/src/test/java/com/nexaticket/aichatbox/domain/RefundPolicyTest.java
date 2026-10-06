// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Chính sách hoàn vé — luật thuần, không có gì để mock. */
class RefundPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Test
    void chua_thanh_toan_thi_khong_co_gi_de_hoan_bat_ke_chinh_sach() {
        assertThat(new RefundPolicy(true, 0).evaluate(null, NOW)).isEqualTo(RefundPolicy.Eligibility.NOT_PAID);
        assertThat(RefundPolicy.NONE.evaluate(null, NOW)).isEqualTo(RefundPolicy.Eligibility.NOT_PAID);
    }

    @Test
    void su_kien_khong_nhan_hoan_ve() {
        assertThat(RefundPolicy.NONE.evaluate(NOW.minus(Duration.ofHours(1)), NOW))
                .isEqualTo(RefundPolicy.Eligibility.NOT_ALLOWED);
    }

    @Test
    void trong_han_thi_du_dieu_kien() {
        RefundPolicy policy = new RefundPolicy(true, 48);

        assertThat(policy.evaluate(NOW.minus(Duration.ofHours(47)), NOW)).isEqualTo(RefundPolicy.Eligibility.ELIGIBLE);
        // Đúng mốc vẫn còn trong hạn: `isAfter` chứ không phải `>=`.
        assertThat(policy.evaluate(NOW.minus(Duration.ofHours(48)), NOW)).isEqualTo(RefundPolicy.Eligibility.ELIGIBLE);
    }

    @Test
    void qua_han_thi_tu_choi() {
        RefundPolicy policy = new RefundPolicy(true, 48);

        assertThat(policy.evaluate(NOW.minus(Duration.ofHours(49)), NOW))
                .isEqualTo(RefundPolicy.Eligibility.WINDOW_PASSED);
    }

    @Test
    void han_bang_khong_la_khong_gioi_han() {
        RefundPolicy policy = new RefundPolicy(true, 0);

        assertThat(policy.evaluate(NOW.minus(Duration.ofDays(400)), NOW)).isEqualTo(RefundPolicy.Eligibility.ELIGIBLE);
    }

    @Test
    void han_am_la_loi_nhap_lieu_khong_phai_chinh_sach() {
        assertThatThrownBy(() -> new RefundPolicy(true, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
