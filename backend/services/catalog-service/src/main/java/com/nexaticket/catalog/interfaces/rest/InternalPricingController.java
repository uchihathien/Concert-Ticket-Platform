// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.PricingQueries;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service cho saga checkout — chỉ service nội bộ gọi được.
 *
 * <p>Đây chính là endpoint mà {@code CatalogPricingAdapter} của ordering-service gọi tới ở bước 3
 * của saga. Trả 4xx khi mã khuyến mãi sai để saga phân biệt được "câu trả lời dứt khoát" với "hạ
 * tầng hỏng" — hai loại đó xử lý khác hẳn nhau.
 */
@RestController
@RequestMapping("/internal/sessions")
public class InternalPricingController {

    private final PricingQueries pricing;

    public InternalPricingController(PricingQueries pricing) {
        this.pricing = pricing;
    }

    public record PricingResponse(int commissionBps, long discountVnd) {}

    @GetMapping("/{eventSessionId}/pricing")
    public PricingResponse pricing(
            @PathVariable UUID eventSessionId,
            @RequestParam(required = false) String promotionCode,
            @RequestParam(defaultValue = "0") long subtotalVnd) {
        var result = pricing.resolve(eventSessionId, promotionCode, subtotalVnd);
        return new PricingResponse(result.commissionBps(), result.discountVnd());
    }
}
