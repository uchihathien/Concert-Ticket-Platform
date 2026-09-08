// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.Promotion;
import com.nexaticket.catalog.domain.port.PromotionRepository;
import com.nexaticket.catalog.domain.port.SeatingPlanRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đường mà saga checkout gọi tới: tỷ lệ hoa hồng hiệu lực và kiểm mã khuyến mãi.
 *
 * <p>Bước này <b>không thay đổi gì</b> ở Catalog, nên saga hỏng ở đây không cần bù trừ
 * (sagas.md §2, bảng bù trừ dòng 3). Đó là lý do nó là một query chứ không phải một command.
 */
@Service
public class PricingQueries {

    private final SeatingPlanRepository sessions;
    private final PromotionRepository promotions;
    private final Clock clock;
    private final int defaultCommissionBps;

    public PricingQueries(
            SeatingPlanRepository sessions,
            PromotionRepository promotions,
            Clock clock,
            @Value("${nexaticket.catalog.commission-bps:500}") int defaultCommissionBps) {
        this.sessions = sessions;
        this.promotions = promotions;
        this.clock = clock;
        this.defaultCommissionBps = defaultCommissionBps;
    }

    /**
     * @param commissionBps tỷ lệ hoa hồng nền tảng tại thời điểm bán, điểm cơ bản (500 = 5%)
     * @param discountVnd tổng giảm giá áp cho đơn
     */
    public record Pricing(int commissionBps, long discountVnd) {}

    @Transactional(readOnly = true)
    public Pricing resolve(UUID eventSessionId, String promotionCode, long subtotalVnd) {
        var timing = sessions.timingOf(eventSessionId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Event session not found"));

        if (promotionCode == null || promotionCode.isBlank()) {
            return new Pricing(defaultCommissionBps, 0L);
        }

        Promotion promotion = promotions
                .findByCode(timing.organizationId(), promotionCode.trim().toUpperCase())
                .filter(found -> found.isUsableAt(clock.instant()))
                .orElseThrow(() -> new ApiException(
                        CatalogErrorCode.PROMOTION_INVALID, "Promotion code is not valid for this session"));

        return new Pricing(defaultCommissionBps, promotion.discountFor(subtotalVnd));
    }
}
