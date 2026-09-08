// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Trần mua vé đã giải quyết kế thừa, sẵn sàng gửi sang Inventory.
 *
 * <p>Giải ở Catalog chứ không ở Inventory là có chủ đích (ADR-1014 §1): Catalog biết cả suất diễn
 * lẫn mặc định nền tảng và chỉ chạy một lần lúc publish, còn Inventory chạy 10k lần/giây lúc mở
 * bán. Đẩy phép tính về phía ít việc là đổi đúng chiều.
 */
public record PurchaseLimits(
        int maxSeatedPerHold, int maxStandingPerHold, int maxUnitsPerHold, int maxTicketsPerCustomer) {

    public PurchaseLimits {
        if (maxSeatedPerHold <= 0 || maxStandingPerHold <= 0 || maxUnitsPerHold <= 0 || maxTicketsPerCustomer <= 0) {
            throw new IllegalArgumentException("Mọi trần mua vé phải > 0");
        }
    }

    /**
     * Kẹp giá trị của suất diễn bằng trần cứng của nền tảng.
     *
     * <p>{@code Math.min} chứ không phải "lấy giá trị của suất nếu có": nếu ban tổ chức khai được
     * số lớn hơn trần nền tảng thì trần nền tảng không còn là trần nữa, và chốt chặn chống đầu cơ
     * vé biến mất mà không ai nhận ra.
     */
    public static PurchaseLimits resolve(
            Integer seated, Integer standing, Integer units, Integer perCustomer, PurchaseLimits platformCap) {
        return new PurchaseLimits(
                clamp(seated, platformCap.maxSeatedPerHold()),
                clamp(standing, platformCap.maxStandingPerHold()),
                clamp(units, platformCap.maxUnitsPerHold()),
                clamp(perCustomer, platformCap.maxTicketsPerCustomer()));
    }

    private static int clamp(Integer requested, int cap) {
        return requested == null ? cap : Math.min(requested, cap);
    }
}
