// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.CatalogProperties;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Báo giá cho một lần checkout: hoa hồng nền tảng và giảm giá.
 *
 * <p>Ordering hỏi hai con số này ở bước 3 của saga, sau khi đã đặt được chỗ và trước khi ghi đơn.
 * Nó <b>không</b> hỏi giá vé — giá đã nằm trong bản sao tồn kho của Inventory và được cộng ở đó.
 * Ranh giới ấy là điều làm cho giá không can thiệp được từ phía khách: request tạo đơn chỉ mang
 * {@code holdId}, không mang tiền.
 *
 * <p><b>Hoa hồng đến từ cấu hình nền tảng, không từ database.</b> Ở MVP mọi tổ chức chung một tỷ
 * lệ (custodial-funds.md §N1). Khi có hợp đồng riêng theo tổ chức thì chỗ đọc sẽ đổi, còn hợp đồng
 * với Ordering thì không — nó vẫn hỏi đúng một câu và nhận đúng hai con số.
 *
 * <p><b>Khuyến mãi chưa tồn tại.</b> Không có bảng, không có màn hình, không có mã nào được phát.
 * Nên mọi mã khách nhập vào đều không hợp lệ, và trả {@code PROMOTION_INVALID} là câu trả lời
 * <i>đúng</i> chứ không phải chỗ chưa làm xong: im lặng bỏ qua mã sẽ khiến khách tưởng đã được
 * giảm giá rồi thấy tổng tiền không đổi, và đó là kiểu lỗi khách gọi lên tổng đài.
 */
@Service
public class PricingQuery {

    private final CatalogQueries queries;
    private final CatalogProperties properties;

    public PricingQuery(CatalogQueries queries, CatalogProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    /**
     * @param commissionBps điểm cơ bản, 500 = 5%
     * @param discountVnd giảm giá cho cả đơn; Ordering tự chia xuống từng dòng
     */
    public record Pricing(int commissionBps, long discountVnd) {}

    public Pricing resolve(UUID eventSessionId, String promotionCode, long subtotalVnd) {
        if (subtotalVnd < 0) {
            throw new IllegalArgumentException("Tạm tính không được âm: " + subtotalVnd);
        }
        if (!queries.sessionExists(eventSessionId)) {
            throw new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Event session not found");
        }
        if (promotionCode != null && !promotionCode.isBlank()) {
            throw new ApiException(CatalogErrorCode.PROMOTION_INVALID, "Promotion code is not valid for this session");
        }
        return new Pricing(properties.commissionBps(), 0L);
    }
}
