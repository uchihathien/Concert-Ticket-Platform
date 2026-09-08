// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.domain.model.HoldRequest;
import com.nexaticket.inventory.domain.model.LimitViolation;
import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.StandingRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Luật trần mua vé — thuần domain, không cần database. */
class PurchaseLimitsTest {

    private static final PurchaseLimits LIMITS = new PurchaseLimits(8, 10, 10, 10);

    @Test
    @DisplayName("Trần riêng đạt nhưng tổng vượt: vẫn từ chối")
    void tong_vuot_tran_du_tung_loai_deu_dat() {
        assertThat(LIMITS.checkHoldSize(6, 6)).contains(LimitViolation.TOO_MANY_UNITS);
        assertThat(LIMITS.checkHoldSize(5, 5)).isEmpty();
    }

    @Test
    @DisplayName("Vé đứng nới hơn vé ngồi: 10 vé đứng được, 10 vé ngồi thì không")
    void ve_dung_noi_hon_ve_ngoi() {
        assertThat(LIMITS.checkHoldSize(0, 10)).isEmpty();
        assertThat(LIMITS.checkHoldSize(10, 0)).contains(LimitViolation.TOO_MANY_SEATED);
    }

    @Test
    @DisplayName("Giữ chỗ rỗng không có nghĩa")
    void giu_cho_rong() {
        assertThat(LIMITS.checkHoldSize(0, 0)).contains(LimitViolation.EMPTY_REQUEST);
    }

    @Test
    @DisplayName("Trần cộng dồn tính cả số vé đang có")
    void tran_cong_don() {
        assertThat(LIMITS.checkCustomerTotal(7, 3)).isEmpty();
        assertThat(LIMITS.checkCustomerTotal(7, 4)).contains(LimitViolation.CUSTOMER_TOTAL_EXCEEDED);
        assertThat(LIMITS.remainingFor(7)).isEqualTo(3);
        assertThat(LIMITS.remainingFor(99)).isZero();
    }

    @Test
    @DisplayName("Ghế trùng lặp trong một request tính là một")
    void ghe_trung_lap_tinh_la_mot() {
        UUID seat = UUID.randomUUID();
        var request = new HoldRequest(List.of(seat, seat, seat), List.of());
        assertThat(request.seatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Hai dòng cùng zone được gộp thành một")
    void gop_dong_cung_zone() {
        var request = new HoldRequest(List.of(), List.of(new StandingRequest("GA", 2), new StandingRequest("GA", 3)));
        assertThat(request.standing()).hasSize(1);
        assertThat(request.standing().get(0).quantity()).isEqualTo(5);
        assertThat(request.standingCount()).isEqualTo(5);
    }
}
