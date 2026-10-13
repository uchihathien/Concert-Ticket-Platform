// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import com.nexaticket.inventory.application.query.SeatStats;
import com.nexaticket.inventory.application.query.SeatStatsQuery;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê tồn kho cho ban tổ chức. Chỉ đọc.
 *
 * <p>TenantFilter đã kiểm người gọi thuộc tổ chức trên đường dẫn; {@code CATALOG_MANAGE} loại nhân
 * viên soát vé.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/stats/inventory")
@RequiresPermission(Permission.CATALOG_MANAGE)
public class OrganizationSeatStatsController {

    private final SeatStatsQuery stats;

    public OrganizationSeatStatsController(SeatStatsQuery stats) {
        this.stats = stats;
    }

    @GetMapping("/stock")
    public SeatStats.Stock stock(@PathVariable UUID organizationId) {
        return stats.stock(organizationId);
    }
}
