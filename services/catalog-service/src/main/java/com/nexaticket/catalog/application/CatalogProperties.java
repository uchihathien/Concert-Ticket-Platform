// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.catalog.domain.model.PurchaseLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trần cứng của nền tảng.
 *
 * <p>Đây là mức trần, không phải mức mặc định gợi ý: ban tổ chức khai số nhỏ hơn thì được, khai số
 * lớn hơn thì bị kẹp xuống (xem {@link PurchaseLimits#resolve}). Nếu không có nó, một ban tổ chức
 * khai "999 vé mỗi lần giữ" là mở cửa cho đầu cơ vé trên chính nền tảng của mình.
 *
 * @param maxTicketsPerCustomer trần cộng dồn cho MỘT tài khoản trong MỘT suất, kể cả vé đã mua
 *     xong ở lần trước (ADR-1014 §2)
 * @param commissionBps hoa hồng nền tảng tính theo điểm cơ bản (500 = 5%, custodial-funds.md §N1).
 *     Ordering hỏi giá trị này lúc tạo đơn rồi <b>chốt luôn vào đơn</b>; đổi ở đây không được làm
 *     sai sổ sách của đơn cũ, và đó là lý do nó được snapshot chứ không tra lại.
 */
@ConfigurationProperties(prefix = "nexaticket.catalog")
public record CatalogProperties(
        int maxSeatedPerHold,
        int maxStandingPerHold,
        int maxUnitsPerHold,
        int maxTicketsPerCustomer,
        int commissionBps,
        boolean demoData,
        java.util.UUID demoOrganizationId) {

    public CatalogProperties {
        if (maxSeatedPerHold <= 0) {
            maxSeatedPerHold = 8;
        }
        if (maxStandingPerHold <= 0) {
            maxStandingPerHold = 10;
        }
        if (maxUnitsPerHold <= 0) {
            maxUnitsPerHold = 10;
        }
        if (maxTicketsPerCustomer <= 0) {
            maxTicketsPerCustomer = 20;
        }
        // Không kẹp về mặc định khi bằng 0: nền tảng miễn hoa hồng là một cấu hình hợp lệ.
        // Chỉ chặn giá trị vô nghĩa, vì ck_commission_bps của ordering đòi 0..10000.
        if (commissionBps < 0 || commissionBps > 10_000) {
            throw new IllegalArgumentException("Hoa hồng phải nằm trong 0..10000 bps, nhận được " + commissionBps);
        }
    }

    public PurchaseLimits platformCap() {
        return new PurchaseLimits(maxSeatedPerHold, maxStandingPerHold, maxUnitsPerHold, maxTicketsPerCustomer);
    }
}
