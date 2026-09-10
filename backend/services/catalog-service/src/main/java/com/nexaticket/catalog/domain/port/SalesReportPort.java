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
