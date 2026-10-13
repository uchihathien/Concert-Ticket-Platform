// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import com.nexaticket.ticketing.application.query.BuyerExportQuery;
import com.nexaticket.ticketing.application.query.GateStats;
import com.nexaticket.ticketing.application.query.GateStatsQuery;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê soát vé cho ban tổ chức. Chỉ đọc.
 *
 * <p>{@code CATALOG_MANAGE}, không phải {@code CHECKIN_SCAN}: nhân viên soát vé chỉ được thấy suất
 * mình được giao (qua màn quét), không được thấy số liệu của cả tổ chức.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/stats/checkins")
@RequiresPermission(Permission.CATALOG_MANAGE)
public class OrganizationGateStatsController {

    private final GateStatsQuery stats;
    private final BuyerExportQuery export;

    public OrganizationGateStatsController(GateStatsQuery stats, BuyerExportQuery export) {
        this.stats = stats;
        this.export = export;
    }

    @GetMapping("/presence")
    public GateStats.Presence presence(@PathVariable UUID organizationId) {
        return stats.presence(organizationId);
    }

    /** Hoạt động soát vé trong kỳ, tính theo thời điểm quét (ngày Việt Nam, hai đầu đều tính). */
    @GetMapping("/activity")
    public GateStats.Activity activity(
            @PathVariable UUID organizationId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> sessionId) {
        return stats.activity(organizationId, date("from", from), date("to", to), ids(sessionId));
    }

    /** Tên người mua (đã che bớt) của một trang đơn hàng. */
    @GetMapping("/buyers")
    public GateStats.Buyers buyers(
            @PathVariable UUID organizationId, @RequestParam(required = false) List<String> orderId) {
        return stats.buyers(organizationId, ids(orderId));
    }

    @GetMapping("/no-shows")
    public GateStats.NoShows noShows(
            @PathVariable UUID organizationId, @RequestParam(required = false) List<String> sessionId) {
        return stats.noShows(organizationId, ids(sessionId));
    }

    /**
     * Xuất danh sách người mua, có tên đầy đủ.
     *
     * <p>Chốt quyền CHẶT hơn phần còn lại của controller: {@code ORG_MEMBERS_MANAGE} chỉ có ở chủ tổ
     * chức và quản trị, nên người quản lý sự kiện xem được thống kê nhưng không mang được danh sách
     * khách ra ngoài. Là POST vì mỗi lần gọi để lại một dòng nhật ký — không phải phép đọc thuần.
     */
    @PostMapping("/buyers/export")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public GateStats.BuyerExport exportBuyers(@PathVariable UUID organizationId, @RequestBody ExportRequest request) {
        UUID actor = TenantContext.requireAuthenticated().userId().value();
        return export.export(organizationId, actor, ids(request == null ? null : request.sessionIds()));
    }

    public record ExportRequest(List<String> sessionIds) {}

    // Tham số nhận dưới dạng chuỗi rồi tự đọc: sai định dạng là lỗi của người gọi (400), không phải 500.
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
