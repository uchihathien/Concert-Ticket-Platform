// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.interfaces.rest;

import com.nexaticket.analytics.application.query.SalesQueries;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard bán hàng của tổ chức.
 *
 * <p>TenantFilter đã kiểm membership từ đường dẫn và trả 404 nếu người dùng không thuộc tổ chức
 * đó, nên controller không phải kiểm lại.
 *
 * <p>Response cố ý chỉ có số vé và số tiền đã bán. Không có endpoint nào ở service này trả hoa
 * hồng, số dư hay lịch chi trả — đó là ranh giới cứng giữa miền của tổ chức và miền tài chính của
 * superadmin (ADR-1010).
 */
@RestController
@RequestMapping("/v1/admin/organizations/{organizationId}")
public class SalesDashboardController {

    private final SalesQueries queries;

    public SalesDashboardController(SalesQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/sales")
    public SalesQueries.OrganizationSummary sales(@PathVariable UUID organizationId) {
        return queries.forOrganization(organizationId);
    }
}
