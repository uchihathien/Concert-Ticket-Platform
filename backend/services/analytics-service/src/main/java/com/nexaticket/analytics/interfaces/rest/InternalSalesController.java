// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.interfaces.rest;

import com.nexaticket.analytics.application.query.SalesQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service của analytics — chỉ service nội bộ gọi được.
 *
 * <p>catalog-service gọi hai endpoint này để ghép doanh thu vào bảng điều khiển của ban tổ chức.
 * Cùng dữ liệu với {@code /v1/admin/organizations/{id}/sales}, khác ở người gọi: đường kia mang JWT
 * của người dùng và được {@code TenantFilter} kiểm membership, còn ở đây người gọi là một service
 * và tự xưng bằng {@code X-Internal-Token}.
 *
 * <p>Kiểm quyền của người dùng cuối vẫn xảy ra — ở phía catalog, trước khi nó gọi tới đây. Nhân đôi
 * kiểm tra đó ở đây là không làm được: request này không mang danh tính người dùng nào.
 *
 * <p>Ranh giới ADR-1010 không đổi: chỉ số vé và tiền đã bán. Service này không có endpoint nào trả
 * hoa hồng, số dư hay lịch chi trả — kể cả trên đường nội bộ.
 */
@RestController
@RequestMapping("/internal")
public class InternalSalesController {

    private final SalesQueries queries;

    public InternalSalesController(SalesQueries queries) {
        this.queries = queries;
    }

    /**
     * Hình dạng JSON là <b>hợp đồng với catalog-service</b>; tên field phải khớp
     * {@code AnalyticsSalesAdapter.SalesResponse}.
     *
     * <p>Bọc danh sách trong một object thay vì trả mảng trần: mảng trần không thêm được trường nào
     * mà không phá hợp đồng, và màn hình này sẽ cần thêm trường.
     */
    public record SalesResponse(List<SessionRow> sessions) {}

    /** @param grossVnd tổng khách trả, KHÔNG phải số tổ chức sẽ nhận */
    public record SessionRow(
            UUID eventSessionId,
            UUID eventId,
            int ticketsSold,
            long grossVnd,
            int ordersPaid,
            int ordersExpired,
            int ordersCancelled) {

        static SessionRow from(SalesQueries.SessionSalesView view) {
            return new SessionRow(
                    view.eventSessionId(),
                    view.eventId(),
                    view.ticketsSold(),
                    view.grossVnd(),
                    view.ordersPaid(),
                    view.ordersExpired(),
                    view.ordersCancelled());
        }
    }

    /** Sự kiện chưa bán được vé nào trả danh sách rỗng, không phải 404 — read model cộng dồn thì "chưa có hàng" là bình thường. */
    @GetMapping("/events/{eventId}/sales")
    public SalesResponse byEvent(@PathVariable UUID eventId) {
        return new SalesResponse(
                queries.forEvent(eventId).stream().map(SessionRow::from).toList());
    }

    @GetMapping("/organizations/{organizationId}/sales")
    public SalesResponse byOrganization(@PathVariable UUID organizationId) {
        return new SalesResponse(queries.forOrganization(organizationId).sessions().stream()
                .map(SessionRow::from)
                .toList());
    }
}
