// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Mã khuyến mãi.
 *
 * @param value với {@code PERCENT} là điểm cơ bản (500 = 5%); với {@code AMOUNT} là số tiền VND
 * @param maxDiscountVnd trần giảm giá cho loại phần trăm; {@code null} là không giới hạn
 */
public record Promotion(
        UUID id,
        UUID organizationId,
        String code,
        Kind kind,
        long value,
        Long maxDiscountVnd,
        Instant startsAt,
        Instant endsAt,
        Integer usageLimit,
        int usedCount,
        boolean active) {

    public enum Kind {
        PERCENT,
        AMOUNT
    }

    public boolean isUsableAt(Instant now) {
        if (!active) {
            return false;
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        if (endsAt != null && !now.isBefore(endsAt)) {
            return false;
        }
        return usageLimit == null || usedCount < usageLimit;
    }

    /**
     * Số tiền giảm cho một đơn.
     *
     * <p>Không bao giờ vượt quá số tiền đơn hàng: giảm giá lớn hơn tổng đơn sẽ cho tổng âm, và
     * sổ cái kép sẽ ghi một khoản phải thu âm — thứ mà constraint của ledger-service từ chối.
     */
    public long discountFor(long subtotalVnd) {
        long raw = kind == Kind.PERCENT ? Math.floorDiv(subtotalVnd * value, 10_000L) : value;
        if (kind == Kind.PERCENT && maxDiscountVnd != null) {
            raw = Math.min(raw, maxDiscountVnd);
        }
        return Math.min(raw, subtotalVnd);
    }
}
