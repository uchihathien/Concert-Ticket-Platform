// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.PricingQuery;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service của Catalog — chỉ service nội bộ gọi được.
 *
 * <p>Gateway cố ý không route {@code /internal/**} ra ngoài. Ở đây rủi ro thấp hơn các endpoint nội
 * bộ khác vì nó chỉ đọc và không đổi gì, nhưng nó vẫn lộ tỷ lệ hoa hồng nền tảng — con số mà
 * ADR-1010 quy định tổ chức <b>không</b> được thấy.
 *
 * <p>Ordering gọi endpoint này ở bước 3 của saga checkout, với hạn 2 giây. Nó phải rẻ: một câu đếm
 * có index và một lần đọc cấu hình, không join, không tính toán.
 */
@RestController
@RequestMapping("/internal/sessions")
public class InternalCatalogController {

    private final PricingQuery pricing;

    public InternalCatalogController(PricingQuery pricing) {
        this.pricing = pricing;
    }

    /**
     * Hình dạng JSON là <b>hợp đồng với ordering-service</b>, viết ra tường minh thay vì trả thẳng
     * record của tầng application. Đổi tên field ở đây là làm hỏng checkout ở runtime, và không có
     * gì lúc biên dịch báo cho biết.
     */
    public record PricingResponse(int commissionBps, long discountVnd) {}

    /**
     * @param promotionCode có thể vắng mặt hoặc rỗng. Chuỗi rỗng được coi như không nhập gì:
     *     {@code UriComponentsBuilder} bên gọi vẫn gắn tên tham số vào URL khi giá trị là null, nên
     *     nếu ở đây phân biệt "rỗng" với "vắng mặt" thì mọi đơn không dùng khuyến mãi đều bị từ chối.
     * @param subtotalVnd tạm tính do Ordering cộng từ giá ghế; chỉ dùng để tính giảm giá theo phần
     *     trăm sau này, KHÔNG dùng để tính tiền phải trả — tiền do Ordering chốt từ dữ liệu của
     *     Inventory
     */
    @GetMapping("/{eventSessionId}/pricing")
    public PricingResponse pricing(
            @PathVariable UUID eventSessionId,
            @RequestParam(required = false) String promotionCode,
            @RequestParam(defaultValue = "0") long subtotalVnd) {

        PricingQuery.Pricing resolved = pricing.resolve(eventSessionId, promotionCode, subtotalVnd);
        return new PricingResponse(resolved.commissionBps(), resolved.discountVnd());
    }
}
