// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.ordering.application.query.SalesStats;
import com.nexaticket.ordering.application.query.SalesStatsQuery;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê bán hàng cho ban tổ chức. Chỉ đọc.
 *
 * <p>Hai lớp chốt: TenantFilter đã kiểm người gọi là thành viên của tổ chức trên đường dẫn (không
 * phải thì 404), và {@code CATALOG_MANAGE} loại nhân viên soát vé — họ là thành viên nhưng doanh thu
 * không phải việc của họ. Mã tổ chức đưa xuống truy vấn luôn là mã trên đường dẫn đã được kiểm.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/stats/orders")
@RequiresPermission(Permission.CATALOG_MANAGE)
public class OrganizationStatsController {

    private final SalesStatsQuery stats;

    public OrganizationStatsController(SalesStatsQuery stats) {
        this.stats = stats;
    }

    @GetMapping("/summary")
    public SalesStats.Summary summary(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId,
            @RequestParam(defaultValue = "false") boolean compare) {
        return stats.summary(request(organizationId, from, to, eventId, sessionId, ticketTypeId), compare);
    }

    @GetMapping("/timeseries")
    public SalesStats.Series timeseries(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId,
            @RequestParam(defaultValue = "day") String bucket,
            @RequestParam(defaultValue = "false") boolean compare) {
        return stats.series(request(organizationId, from, to, eventId, sessionId, ticketTypeId), bucket, compare);
    }

    @GetMapping("/breakdown")
    public SalesStats.Breakdown breakdown(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId) {
        return stats.breakdown(request(organizationId, from, to, eventId, sessionId, ticketTypeId));
    }

    @GetMapping("/heatmap")
    public SalesStats.Heatmap heatmap(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId) {
        return stats.heatmap(request(organizationId, from, to, eventId, sessionId, ticketTypeId));
    }

    @GetMapping("/distribution")
    public SalesStats.Distribution distribution(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId) {
        return stats.distribution(request(organizationId, from, to, eventId, sessionId, ticketTypeId));
    }

    /** Người mua mới và quay lại. Chỉ trả số đếm. */
    @GetMapping("/customers")
    public SalesStats.Customers customers(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId) {
        return stats.customers(request(organizationId, from, to, eventId, sessionId, ticketTypeId));
    }

    /** Danh sách đơn có phân trang. Không mang dữ liệu cá nhân của người mua. */
    @GetMapping("/list")
    public SalesStats.OrderPage list(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> eventId,
            @RequestParam(required = false) List<String> sessionId,
            @RequestParam(required = false) List<String> ticketTypeId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return stats.orders(
                request(organizationId, from, to, eventId, sessionId, ticketTypeId), status, query, page, size);
    }

    /**
     * Tham số nhận dưới dạng chuỗi rồi tự đọc: một ngày hay một mã sai định dạng là lỗi của người gọi
     * (400), không phải lỗi của máy chủ. Để Spring tự ép kiểu thì trường hợp ấy thành 500.
     */
    private static SalesStatsQuery.Request request(
            UUID organizationId,
            String from,
            String to,
            List<String> eventIds,
            List<String> sessionIds,
            List<String> ticketTypeIds) {
        return new SalesStatsQuery.Request(
                organizationId, date("from", from), date("to", to), ids(eventIds), ids(sessionIds), ids(ticketTypeIds));
    }

    private static LocalDate date(String name, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, name + " phải có dạng YYYY-MM-DD");
        }
    }

    private static List<UUID> ids(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return values.stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Mã trong bộ lọc không hợp lệ");
        }
    }
}
