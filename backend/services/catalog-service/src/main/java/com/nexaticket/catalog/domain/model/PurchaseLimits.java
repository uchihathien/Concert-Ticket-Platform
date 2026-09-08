// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Trần mua vé ở một tầng cấu hình. {@code null} nghĩa là kế thừa tầng trên (ADR-1014 §1).
 *
 * <p>Cùng tên với lớp trong inventory-service nhưng khác hẳn về ý nghĩa, và đó là chuyện bình
 * thường giữa hai bounded context: ở đây các trường <b>có thể null</b> vì đang mô tả một tầng cấu
 * hình chưa giải quyết kế thừa; ở Inventory chúng luôn có giá trị vì đã được giải quyết xong.
 */
public record PurchaseLimits(
        Integer maxSeatedPerHold, Integer maxStandingPerHold, Integer maxUnitsPerHold, Integer maxTicketsPerCustomer) {

    public static final PurchaseLimits INHERIT_ALL = new PurchaseLimits(null, null, null, null);

    /**
     * Giải quyết kế thừa ba tầng: suất diễn → tổ chức → nền tảng.
     *
     * <p>Sau đó <b>kẹp lại bằng trần cứng của nền tảng</b>. Việc kẹp là cần thiết vì trần cứng có
     * thể bị hạ SAU khi tổ chức đã lưu một giá trị cao hơn: lúc lưu thì hợp lệ, lúc materialize
     * thì không còn. Kiểm ở lúc lưu là để báo lỗi sớm cho người dùng; kẹp ở đây là để không bao
     * giờ vượt trần dù chuyện gì xảy ra ở giữa.
     *
     * @param platform trần cứng của nền tảng — mọi trường bắt buộc có giá trị
     */
    public static SeatManifest.ResolvedPurchaseLimits resolve(
            PurchaseLimits session, PurchaseLimits organization, PurchaseLimits platform) {
        return new SeatManifest.ResolvedPurchaseLimits(
                clamp(session.maxSeatedPerHold(), organization.maxSeatedPerHold(), platform.maxSeatedPerHold()),
                clamp(session.maxStandingPerHold(), organization.maxStandingPerHold(), platform.maxStandingPerHold()),
                clamp(session.maxUnitsPerHold(), organization.maxUnitsPerHold(), platform.maxUnitsPerHold()),
                clamp(
                        session.maxTicketsPerCustomer(),
                        organization.maxTicketsPerCustomer(),
                        platform.maxTicketsPerCustomer()));
    }

    private static int clamp(Integer session, Integer organization, Integer platformCeiling) {
        int effective = session != null ? session : (organization != null ? organization : platformCeiling);
        return Math.min(effective, platformCeiling);
    }

    /** Có trường nào vượt trần cứng của nền tảng không — kiểm lúc LƯU để báo lỗi sớm. */
    public boolean exceeds(PurchaseLimits platformCeiling) {
        return over(maxSeatedPerHold, platformCeiling.maxSeatedPerHold())
                || over(maxStandingPerHold, platformCeiling.maxStandingPerHold())
                || over(maxUnitsPerHold, platformCeiling.maxUnitsPerHold())
                || over(maxTicketsPerCustomer, platformCeiling.maxTicketsPerCustomer());
    }

    private static boolean over(Integer value, Integer ceiling) {
        return value != null && ceiling != null && value > ceiling;
    }
}
