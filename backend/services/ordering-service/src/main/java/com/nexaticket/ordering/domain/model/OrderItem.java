// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

import com.nexaticket.kernel.money.Money;
import java.util.UUID;

/**
 * Một chỗ trong đơn — <b>snapshot</b>, không phải tham chiếu.
 *
 * <p>Nhãn ghế, tên hạng vé, đơn giá và tỷ lệ hoa hồng đều được sao vào đây lúc bán. Sáu tháng sau,
 * khi tổ chức đã đổi tên hạng vé và nền tảng đã đổi biểu phí, hoá đơn của đơn cũ vẫn phải in ra
 * đúng những gì khách đã thấy.
 *
 * @param commissionBps tỷ lệ hoa hồng tại thời điểm bán, tính bằng điểm cơ bản (500 = 5%)
 */
public record OrderItem(
        UUID id,
        UUID sessionSeatId,
        String seatCode,
        String zoneCode,
        String admissionType,
        String seatLabel,
        UUID ticketTypeId,
        String ticketTypeName,
        Money unitPrice,
        Money discount,
        int commissionBps) {

    public OrderItem {
        if (commissionBps < 0 || commissionBps > 10_000) {
            throw new IllegalArgumentException("commissionBps ngoài khoảng 0..10000: " + commissionBps);
        }
        if (discount.isGreaterThan(unitPrice)) {
            throw new IllegalArgumentException("Giảm giá không được vượt đơn giá");
        }
    }

    public Money netPrice() {
        return unitPrice.minus(discount);
    }

    /**
     * Hoa hồng tính trên giá <b>sau</b> giảm giá.
     *
     * <p>Tính trên giá gốc sẽ khiến nền tảng ăn hoa hồng trên phần tiền chưa từng thu được — và
     * với khuyến mãi sâu, phần chia cho tổ chức có thể âm.
     */
    public Money commission() {
        return netPrice().percentOf(commissionBps);
    }
}
