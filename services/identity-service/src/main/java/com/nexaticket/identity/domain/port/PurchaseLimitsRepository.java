// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.TenantId;
import java.util.Optional;

/**
 * Trần mua vé mặc định của một tổ chức (ADR-1014).
 *
 * <p>Mỗi trường {@code null} nghĩa là "kế thừa trần nền tảng", không phải "không giới hạn". Phân
 * biệt đó quan trọng: một tổ chức để trống ô nào thì ô đó vẫn được nền tảng bảo vệ.
 *
 * <p>Bảng này chỉ là <b>nơi khai báo</b>. Nó không được đọc ở đường nóng: catalog-service giải
 * quyết kế thừa một lần lúc publish rồi gửi giá trị đã chốt sang Inventory, nên lúc mở bán không
 * service nào phải hỏi identity xem trần là bao nhiêu.
 */
public interface PurchaseLimitsRepository {

    record Limits(
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer) {

        public static Limits empty() {
            return new Limits(null, null, null, null);
        }
    }

    Optional<Limits> find(TenantId organizationId);

    void save(TenantId organizationId, Limits limits);
}
