// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

import java.util.Optional;

/**
 * Trần mua vé đã giải quyết kế thừa, sao vào {@code session_inventory} lúc materialize.
 *
 * <p>Ba tầng cấu hình {@code coalesce(suất diễn, tổ chức, nền tảng)} được giải quyết ở Catalog lúc
 * publish, không phải ở đây (ADR-1014 §1). Inventory chỉ đọc kết quả — đó là lý do đường giữ chỗ
 * không bao giờ gọi service khác, kể cả ở 10k đồng thời.
 *
 * @param maxSeatedPerHold trần vé ngồi mỗi lần giữ chỗ
 * @param maxStandingPerHold trần vé đứng mỗi lần giữ chỗ
 * @param maxUnitsPerHold trần tổng mỗi lần giữ chỗ
 * @param maxTicketsPerCustomer trần cộng dồn mỗi tài khoản trên suất diễn
 */
public record PurchaseLimits(
        int maxSeatedPerHold, int maxStandingPerHold, int maxUnitsPerHold, int maxTicketsPerCustomer) {

    public PurchaseLimits {
        requirePositive(maxSeatedPerHold, "maxSeatedPerHold");
        requirePositive(maxStandingPerHold, "maxStandingPerHold");
        requirePositive(maxUnitsPerHold, "maxUnitsPerHold");
        requirePositive(maxTicketsPerCustomer, "maxTicketsPerCustomer");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " phải dương, nhận " + value);
        }
    }

    /**
     * Kiểm tra trần mỗi lần giữ chỗ.
     *
     * <p>Trần này <b>không phải công cụ chống phe vé</b> — ai muốn gom 100 vé chỉ cần đặt 10 đơn
     * liên tiếp. Nó chỉ chặn một request bất thường. Công cụ chống gom vé thật sự là
     * {@link #checkCustomerTotal} (ADR-1014).
     */
    public Optional<LimitViolation> checkHoldSize(int seatedCount, int standingCount) {
        if (seatedCount + standingCount == 0) {
            return Optional.of(LimitViolation.EMPTY_REQUEST);
        }
        if (seatedCount > maxSeatedPerHold) {
            return Optional.of(LimitViolation.TOO_MANY_SEATED);
        }
        if (standingCount > maxStandingPerHold) {
            return Optional.of(LimitViolation.TOO_MANY_STANDING);
        }
        if (seatedCount + standingCount > maxUnitsPerHold) {
            return Optional.of(LimitViolation.TOO_MANY_UNITS);
        }
        return Optional.empty();
    }

    /**
     * Kiểm tra trần cộng dồn.
     *
     * @param alreadyHeld số chỗ người này đang giữ / đã đặt / đã mua ở suất diễn này
     * @param requested số chỗ đang xin thêm
     */
    public Optional<LimitViolation> checkCustomerTotal(int alreadyHeld, int requested) {
        return alreadyHeld + requested > maxTicketsPerCustomer
                ? Optional.of(LimitViolation.CUSTOMER_TOTAL_EXCEEDED)
                : Optional.empty();
    }

    /** Còn mua được bao nhiêu vé nữa — hiện cho khách trước khi họ chọn (ADR-1014 §4). */
    public int remainingFor(int alreadyHeld) {
        return Math.max(0, maxTicketsPerCustomer - alreadyHeld);
    }
}
