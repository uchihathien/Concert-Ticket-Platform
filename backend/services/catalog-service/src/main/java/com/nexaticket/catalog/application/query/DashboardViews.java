// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO của bảng điều khiển tổ chức.
 *
 * <p>Đây là màn hình duy nhất trong hệ thống ghép dữ liệu của <b>ba</b> service: catalog (sự kiện,
 * khu vực, hạng vé), inventory (trạng thái từng chỗ) và analytics (vé bán, doanh thu). Ghép ở đây
 * chứ không ở frontend vì thứ tự gọi, cách gộp và cách xử lý khi một bên im lặng là quy tắc nghiệp
 * vụ — ba app frontend tự ghép là ba bản sao của cùng quy tắc đó.
 *
 * <p>Ranh giới ADR-1010 giữ nguyên: {@code grossVnd} là tổng khách trả, không phải số tổ chức sẽ
 * nhận. Không có trường nào ở đây mang hoa hồng, số dư hay lịch chi trả.
 */
public final class DashboardViews {

    private DashboardViews() {}

    /**
     * Tổng quan: danh sách sự kiện của tổ chức kèm số liệu cộng dồn.
     *
     * @param degraded tên những service không hỏi được ở lần dựng này. <b>Không</b> phải chi tiết
     *     kỹ thuật thừa: nếu analytics im lặng thì {@code grossVnd} là 0 vì không hỏi được, chứ
     *     không phải vì chưa bán được đồng nào — và hai điều đó nói ngược nhau về công việc của
     *     ban tổ chức. Trường này là thứ cho phép màn hình nói "chưa có số" thay vì nói "0đ".
     */
    public record OrganizationDashboard(
            UUID organizationId,
            DashboardTotals totals,
            List<CatalogViews.AdminEventRow> events,
            List<String> degraded) {}

    /** @param capacity tổng sức chứa đã khai của mọi sự kiện, kể cả bản nháp */
    public record DashboardTotals(
            int eventCount, int publishedCount, int draftCount, int capacity, int ticketsSold, long grossVnd) {}

    /**
     * Master data đầy đủ của một sự kiện.
     *
     * @param event thông tin chi tiết, khu vực và hạng vé — cùng hình dạng với màn hình sửa sự
     *     kiện, nên frontend dùng lại được component đã có
     * @param sessions theo từng suất: trạng thái chỗ và số bán. Cố ý KHÔNG lặp lại thông tin suất
     *     đã có trong {@code event.sessions()} — hai bản sao trong một payload là hai bản sẽ lệch
     */
    public record EventMasterData(
            CatalogViews.AdminEventDetail event,
            List<SessionReport> sessions,
            MasterDataTotals totals,
            List<String> degraded) {}

    /**
     * @param seating {@code null} khi suất chưa được dựng tồn kho (sự kiện còn nháp) hoặc khi
     *     inventory không trả lời. Phân biệt hai trường hợp bằng {@code degraded}.
     * @param sales {@code null} khi suất chưa bán được vé nào hoặc analytics không trả lời
     */
    public record SessionReport(UUID eventSessionId, Instant startsAt, SessionSeating seating, SessionSales sales) {}

    /** @param availabilityVersion số liệu này ứng với lần thay đổi tồn kho nào */
    public record SessionSeating(long availabilityVersion, SeatCounts totals, List<ZoneSeatCounts> zones) {}

    /**
     * Đếm theo trạng thái chứ không liệt kê từng chỗ.
     *
     * <p>"Trạng thái từng ghế" mà màn hình cần là <b>sơ đồ</b>, và sơ đồ đã có endpoint riêng của
     * nó — {@code GET /v1/sessions/{id}/seats} ở inventory, có ETag và được gọi vài nghìn lần mỗi
     * phút lúc mở bán. Nhét 5.000 dòng ghế vào payload này là dựng đường thứ hai cho cùng dữ liệu,
     * chậm hơn và không có cache.
     */
    public record ZoneSeatCounts(
            String zoneCode,
            String admissionType,
            int available,
            int held,
            int reserved,
            int sold,
            int blocked,
            int total) {}

    public record SeatCounts(int available, int held, int reserved, int sold, int blocked, int total) {}

    /** @param grossVnd tổng khách trả, KHÔNG phải số tổ chức sẽ nhận (ADR-1010) */
    public record SessionSales(
            int ticketsSold, long grossVnd, int ordersPaid, int ordersExpired, int ordersCancelled) {}

    /**
     * @param declaredCapacity sức chứa khai trong catalog — có ngay cả khi sự kiện còn nháp
     * @param materializedSeats số chỗ thật sự đã dựng ở inventory; lệch với {@code declaredCapacity}
     *     nghĩa là sơ đồ đã đổi sau lần publish gần nhất, và đó là thứ đáng để mắt
     */
    public record MasterDataTotals(
            int declaredCapacity, int materializedSeats, int seatsSold, int ticketsSold, long grossVnd) {}
}
