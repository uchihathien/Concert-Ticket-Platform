// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.application.query;

import com.nexaticket.analytics.domain.port.SalesReadModelRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard của tổ chức.
 *
 * <p>Đây là <b>toàn bộ</b> những gì tổ chức được thấy về tiền: số vé đã bán và số tiền đã bán. Họ
 * không thấy hoa hồng nền tảng cắt bao nhiêu, không thấy số dư, không thấy lịch chi trả — những
 * thứ đó thuộc miền tài chính của superadmin (ADR-1010).
 */
@Service
public class SalesQueries {

    private final SalesReadModelRepository readModel;

    public SalesQueries(SalesReadModelRepository readModel) {
        this.readModel = readModel;
    }

    /**
     * @param ticketsSold số vé đã bán
     * @param grossVnd số tiền đã bán — tổng khách trả, KHÔNG phải số tổ chức sẽ nhận
     */
    public record SessionSalesView(
            UUID eventSessionId,
            UUID eventId,
            int ticketsSold,
            long grossVnd,
            int ordersPaid,
            int ordersExpired,
            int ordersCancelled) {}

    /** @param totalGrossVnd tổng tiền đã bán của cả tổ chức */
    public record OrganizationSummary(
            UUID organizationId, int totalTicketsSold, long totalGrossVnd, List<SessionSalesView> sessions) {}

    @Transactional(readOnly = true)
    public OrganizationSummary forOrganization(UUID organizationId) {
        List<SessionSalesView> sessions = readModel.byOrganization(organizationId).stream()
                .map(row -> new SessionSalesView(
                        row.eventSessionId(),
                        row.eventId(),
                        row.ticketsSold(),
                        row.grossVnd(),
                        row.ordersPaid(),
                        row.ordersExpired(),
                        row.ordersCancelled()))
                .toList();

        return new OrganizationSummary(
                organizationId,
                sessions.stream().mapToInt(SessionSalesView::ticketsSold).sum(),
                sessions.stream().mapToLong(SessionSalesView::grossVnd).sum(),
                sessions);
    }
}
