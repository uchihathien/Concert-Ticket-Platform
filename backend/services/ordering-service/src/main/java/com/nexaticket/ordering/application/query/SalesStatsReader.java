// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import java.util.List;

/**
 * Cổng đọc số liệu tổng hợp. Chỉ đọc; mọi phép gộp chạy trong database.
 *
 * <p>Nằm ở tầng application chứ không ở {@code domain.port}: nó trả về hình dạng của màn hình thống
 * kê, không phải aggregate, và controller được phép dùng thẳng các record ấy.
 */
public interface SalesStatsReader {

    SalesStats.Totals totals(SalesStats.Filter filter);

    /** @param unit {@code hour}, {@code day} hoặc {@code week}; chỉ trả những ô có đơn */
    List<SalesStats.Point> series(SalesStats.Filter filter, String unit);

    List<SalesStats.SessionRow> bySession(SalesStats.Filter filter);

    List<SalesStats.TicketTypeRow> byTicketType(SalesStats.Filter filter);

    List<SalesStats.HeatCell> heatmap(SalesStats.Filter filter);

    List<SalesStats.SizeBucket> ticketsPerOrder(SalesStats.Filter filter);

    /**
     * @param statuses rỗng = mọi trạng thái
     * @param orderNumberPrefix {@code null} = không tìm theo mã đơn
     */
    List<SalesStats.OrderRow> orders(
            SalesStats.Filter filter, List<String> statuses, String orderNumberPrefix, int limit, int offset);

    long countOrders(SalesStats.Filter filter, List<String> statuses, String orderNumberPrefix);

    /** Mảng bốn số: người mua, quay lại, mua lặp lại trong kỳ — theo đúng thứ tự của bản ghi Customers. */
    long[] buyerCounts(SalesStats.Filter filter);

    /** {@code tickets} của mỗi phần tử ở đây là SỐ ĐƠN đã trả của một người; {@code orders} là số người. */
    List<SalesStats.SizeBucket> ordersPerBuyer(SalesStats.Filter filter);
}
