// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.domain.model.Promotion;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Luật khuyến mãi. */
class PromotionTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    @DisplayName("Giảm phần trăm tính theo điểm cơ bản")
    void giam_phan_tram() {
        assertThat(percent(1_000, null).discountFor(3_000_000L)).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("Trần giảm giá chặn phần trăm trên đơn lớn")
    void tran_giam_gia() {
        assertThat(percent(5_000, 200_000L).discountFor(3_000_000L)).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("Giảm giá không bao giờ vượt số tiền đơn hàng")
    void khong_vuot_tong_don() {
        // Giảm lớn hơn tổng đơn sẽ cho tổng âm, và sổ cái kép sẽ ghi một khoản phải thu âm —
        // thứ mà constraint của ledger-service từ chối.
        assertThat(amount(5_000_000L).discountFor(1_000_000L)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("Hết lượt dùng, hết hạn, hoặc bị tắt thì không dùng được")
    void khong_dung_duoc() {
        assertThat(promotion(Promotion.Kind.AMOUNT, 100L, null, null, null, 5, 5, true)
                        .isUsableAt(NOW))
                .isFalse();
        assertThat(promotion(Promotion.Kind.AMOUNT, 100L, null, null, NOW.minus(1, ChronoUnit.DAYS), null, 0, true)
                        .isUsableAt(NOW))
                .isFalse();
        assertThat(promotion(Promotion.Kind.AMOUNT, 100L, null, null, null, null, 0, false)
                        .isUsableAt(NOW))
                .isFalse();
    }

    private static Promotion percent(long bps, Long maxDiscount) {
        return promotion(Promotion.Kind.PERCENT, bps, maxDiscount, null, null, null, 0, true);
    }

    private static Promotion amount(long value) {
        return promotion(Promotion.Kind.AMOUNT, value, null, null, null, null, 0, true);
    }

    @SuppressWarnings("java:S107")
    private static Promotion promotion(
            Promotion.Kind kind,
            long value,
            Long maxDiscount,
            Instant startsAt,
            Instant endsAt,
            Integer usageLimit,
            int usedCount,
            boolean active) {
        return new Promotion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GIAM10",
                kind,
                value,
                maxDiscount,
                startsAt,
                endsAt,
                usageLimit,
                usedCount,
                active);
    }
}
