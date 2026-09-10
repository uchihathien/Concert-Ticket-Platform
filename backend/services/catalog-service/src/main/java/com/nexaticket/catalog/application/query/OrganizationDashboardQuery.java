// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.SalesReportPort;
import com.nexaticket.catalog.domain.port.SeatStatusPort;
import com.nexaticket.catalog.domain.port.UpstreamUnavailableException;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bảng điều khiển của tổ chức: ghép catalog + inventory + analytics thành một màn hình.
 *
 * <h3>Vì sao ghép ở đây chứ không ở frontend</h3>
 *
 * <p>Bốn app frontend đều cần con số này, và quy tắc gộp không tầm thường: sức chứa khai ở catalog
 * khác số chỗ đã dựng ở inventory, "đã bán" đếm theo chỗ ở inventory nhưng đếm theo vé ở analytics,
 * và một suất chưa publish thì không có số nào cả. Bốn bản sao của quy tắc đó sẽ lệch nhau, và bản
 * lệch là bản ban tổ chức dùng để ra quyết định.
 *
 * <h3>Một service im lặng không được làm hỏng cả màn hình</h3>
 *
 * <p>Đây là màn hình <b>đọc</b>, và phần quan trọng nhất của nó — sự kiện, khu vực, hạng vé — nằm
 * ngay trong database của chính service này. Để analytics chết kéo theo 500 cho cả trang là đánh
 * đổi ngược: mất tất cả để khỏi mất một phần.
 *
 * <p>Nên hỏng thì trả phần còn lại kèm {@code degraded}. Cái không được phép làm là im lặng trả 0:
 * "chưa bán được đồng nào" và "không hỏi được doanh thu" là hai câu khác nhau, và nhầm chúng là
 * báo sai cho ban tổ chức về chính tiền của họ.
 */
@Service
public class OrganizationDashboardQuery {

    private static final Logger log = LoggerFactory.getLogger(OrganizationDashboardQuery.class);

    private final EventRepository events;
    private final VenueRepository venues;
    private final CatalogQueries queries;
    private final SeatStatusPort seatStatus;
    private final SalesReportPort salesReport;
    private final CatalogAccess access;

    public OrganizationDashboardQuery(
            EventRepository events,
            VenueRepository venues,
            CatalogQueries queries,
            SeatStatusPort seatStatus,
            SalesReportPort salesReport,
            CatalogAccess access) {
        this.events = events;
        this.venues = venues;
        this.queries = queries;
        this.seatStatus = seatStatus;
        this.salesReport = salesReport;
        this.access = access;
    }

    /** Tổng quan: mọi sự kiện của tổ chức, kèm số vé và tiền đã bán của cả tổ chức. */
    @Transactional(readOnly = true)
    public DashboardViews.OrganizationDashboard overview(UUID organizationId) {
        access.requireCatalogManager(organizationId);

        List<CatalogViews.AdminEventRow> rows = queries.organizationEvents(organizationId);
        Set<String> degraded = new LinkedHashSet<>();

        List<SalesReportPort.SessionSales> sales = List.of();
        try {
            sales = salesReport.forOrganization(organizationId);
        } catch (UpstreamUnavailableException e) {
            log.warn("Không hỏi được doanh thu của tổ chức {}: {}", organizationId, e.getMessage());
            degraded.add(e.service());
        }

        DashboardViews.DashboardTotals totals = new DashboardViews.DashboardTotals(
                rows.size(),
                (int) rows.stream()
                        .filter(r -> EventStatus.PUBLISHED.name().equals(r.status()))
                        .count(),
                (int) rows.stream()
                        .filter(r -> EventStatus.DRAFT.name().equals(r.status()))
                        .count(),
                rows.stream().mapToInt(CatalogViews.AdminEventRow::capacity).sum(),
                sales.stream()
                        .mapToInt(SalesReportPort.SessionSales::ticketsSold)
                        .sum(),
                sales.stream().mapToLong(SalesReportPort.SessionSales::grossVnd).sum());

        return new DashboardViews.OrganizationDashboard(organizationId, totals, rows, List.copyOf(degraded));
    }

    /**
     * Master data đầy đủ của một sự kiện: chi tiết, khu vực, trạng thái chỗ, số bán.
     *
     * <p>Một lời gọi sang analytics cho cả sự kiện, nhưng một lời gọi sang inventory cho <b>mỗi
     * suất</b>: tồn kho được đánh khoá theo suất diễn (ADR-1002), nên không có câu hỏi "cả sự kiện"
     * nào để hỏi. Một sự kiện có vài suất, không phải vài trăm.
     */
    @Transactional(readOnly = true)
    public DashboardViews.EventMasterData masterData(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        Venue venue = venues.findById(organizationId, event.venueId())
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        Set<String> degraded = new LinkedHashSet<>();
        Map<UUID, SalesReportPort.SessionSales> salesBySession = salesBySession(eventId, degraded);

        List<DashboardViews.SessionReport> reports = new ArrayList<>();
        for (EventSession session : event.sessions()) {
            reports.add(new DashboardViews.SessionReport(
                    session.id(),
                    session.startsAt(),
                    seatingOf(session.id(), degraded),
                    toSales(salesBySession.get(session.id()))));
        }

        return new DashboardViews.EventMasterData(
                AdminCatalogQuery.toDetail(event, venue), reports, totals(venue, reports), List.copyOf(degraded));
    }

    private Map<UUID, SalesReportPort.SessionSales> salesBySession(UUID eventId, Set<String> degraded) {
        try {
            Map<UUID, SalesReportPort.SessionSales> bySession = new HashMap<>();
            for (SalesReportPort.SessionSales row : salesReport.forEvent(eventId)) {
                bySession.put(row.eventSessionId(), row);
            }
            return bySession;
        } catch (UpstreamUnavailableException e) {
            log.warn("Không hỏi được doanh thu của sự kiện {}: {}", eventId, e.getMessage());
            degraded.add(e.service());
            return Map.of();
        }
    }

    private DashboardViews.SessionSeating seatingOf(UUID sessionId, Set<String> degraded) {
        Optional<SeatStatusPort.SessionSeatStatus> status;
        try {
            status = seatStatus.forSession(sessionId);
        } catch (UpstreamUnavailableException e) {
            log.warn("Không hỏi được tồn kho của suất {}: {}", sessionId, e.getMessage());
            degraded.add(e.service());
            return null;
        }
        // Rỗng là câu trả lời bình thường, không phải hỏng: suất chưa publish thì chưa có tồn kho.
        return status.map(OrganizationDashboardQuery::toSeating).orElse(null);
    }

    private static DashboardViews.SessionSeating toSeating(SeatStatusPort.SessionSeatStatus status) {
        List<DashboardViews.ZoneSeatCounts> zones = status.zones().stream()
                .map(z -> new DashboardViews.ZoneSeatCounts(
                        z.zoneCode(),
                        z.admissionType(),
                        z.available(),
                        z.held(),
                        z.reserved(),
                        z.sold(),
                        z.blocked(),
                        z.total()))
                .toList();

        DashboardViews.SeatCounts totals = new DashboardViews.SeatCounts(
                zones.stream()
                        .mapToInt(DashboardViews.ZoneSeatCounts::available)
                        .sum(),
                zones.stream().mapToInt(DashboardViews.ZoneSeatCounts::held).sum(),
                zones.stream().mapToInt(DashboardViews.ZoneSeatCounts::reserved).sum(),
                zones.stream().mapToInt(DashboardViews.ZoneSeatCounts::sold).sum(),
                zones.stream().mapToInt(DashboardViews.ZoneSeatCounts::blocked).sum(),
                zones.stream().mapToInt(DashboardViews.ZoneSeatCounts::total).sum());

        return new DashboardViews.SessionSeating(status.availabilityVersion(), totals, zones);
    }

    private static DashboardViews.SessionSales toSales(SalesReportPort.SessionSales row) {
        return row == null
                ? null
                : new DashboardViews.SessionSales(
                        row.ticketsSold(),
                        row.grossVnd(),
                        row.ordersPaid(),
                        row.ordersExpired(),
                        row.ordersCancelled());
    }

    /**
     * Sức chứa nhân theo số suất: mỗi suất bán lại toàn bộ khán phòng.
     *
     * <p>Lấy thẳng {@code venue.capacity()} sẽ báo một sự kiện ba suất là 5.000 chỗ trong khi nó
     * thật sự bán 15.000 — và tỷ lệ lấp đầy hiện trên màn hình sẽ gấp ba lần sự thật.
     */
    private static DashboardViews.MasterDataTotals totals(Venue venue, List<DashboardViews.SessionReport> reports) {
        int materialized = reports.stream()
                .filter(r -> r.seating() != null)
                .mapToInt(r -> r.seating().totals().total())
                .sum();
        int seatsSold = reports.stream()
                .filter(r -> r.seating() != null)
                .mapToInt(r -> r.seating().totals().sold())
                .sum();
        int ticketsSold = reports.stream()
                .filter(r -> r.sales() != null)
                .mapToInt(r -> r.sales().ticketsSold())
                .sum();
        long grossVnd = reports.stream()
                .filter(r -> r.sales() != null)
                .mapToLong(r -> r.sales().grossVnd())
                .sum();

        return new DashboardViews.MasterDataTotals(
                venue.capacity() * reports.size(), materialized, seatsSold, ticketsSold, grossVnd);
    }
}
