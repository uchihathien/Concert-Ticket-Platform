// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Số vé bán và tiền đã bán, đọc từ analytics-service.
 *
 * <p>Đây là <b>toàn bộ</b> phần tiền mà tổ chức được thấy: không hoa hồng, không số dư, không lịch
 * chi trả (ADR-1010). Cổng này cố ý không có phương thức nào trả những thứ đó — một cổng hẹp là
 * ranh giới khó đi vòng hơn một dòng ghi chú.
 */
public interface SalesReportPort {

    /** @throws UpstreamUnavailableException khi không hỏi được analytics-service */
    List<SessionSales> forEvent(UUID eventId);

    /** @throws UpstreamUnavailableException khi không hỏi được analytics-service */
    List<SessionSales> forOrganization(UUID organizationId);

    /**
     * Sự kiện bán chạy nhất, xếp giảm dần.
     *
     * <p>Trả về <b>thứ tự</b>, không trả về số vé: trang chủ chỉ cần biết xếp ai trước ai, còn số
     * vé một sự kiện bán được là con số kinh doanh của ban tổ chức ấy. Cổng không nhận thứ mà nó
     * không dùng — có nhận thì sớm muộn sẽ có người hiển thị nó ra.
     */
    List<UUID> trendingEventIds(int limit);

    /** @param grossVnd tổng khách trả, KHÔNG phải số tổ chức sẽ nhận */
    record SessionSales(
            UUID eventSessionId,
            UUID eventId,
            int ticketsSold,
            long grossVnd,
            int ordersPaid,
            int ordersExpired,
            int ordersCancelled) {}
}
