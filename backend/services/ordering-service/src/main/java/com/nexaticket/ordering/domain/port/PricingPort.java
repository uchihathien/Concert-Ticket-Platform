// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import java.util.UUID;

/**
 * Gọi catalog-service để lấy tỷ lệ hoa hồng hiệu lực và kiểm mã khuyến mãi.
 *
 * <p>Bước này <b>không thay đổi gì</b> ở Catalog, nên hỏng ở đây không cần bù trừ (sagas.md §2,
 * bảng bù trừ dòng 3).
 */
public interface PricingPort {

    /**
     * @param promotionCode có thể null
     * @throws PromotionInvalidException khi mã khuyến mãi sai hoặc hết hạn
     * @throws RemoteCallException khi catalog-service không phản hồi trong hạn
     */
    Pricing resolve(UUID eventSessionId, String promotionCode, long subtotalVnd);

    /**
     * @param commissionBps tỷ lệ hoa hồng nền tảng tại thời điểm bán, điểm cơ bản (500 = 5%)
     * @param discountVnd tổng giảm giá áp cho đơn
     */
    record Pricing(int commissionBps, long discountVnd) {}

    class PromotionInvalidException extends RuntimeException {
        public PromotionInvalidException(String message) {
            super(message);
        }
    }
}
