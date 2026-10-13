// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.SessionDirectory;
import com.nexaticket.catalog.application.query.SessionDirectoryQuery;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh bạ suất diễn cho các trang thống kê của ban tổ chức. Chỉ đọc.
 *
 * <p>TenantFilter đã kiểm người gọi thuộc tổ chức trên đường dẫn; {@code CATALOG_MANAGE} loại nhân
 * viên soát vé — họ đã có danh sách suất của riêng mình ở {@code /checkin-sessions}.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/stats/catalog")
@RequiresPermission(Permission.CATALOG_MANAGE)
public class OrganizationSessionDirectoryController {

    private final SessionDirectoryQuery directory;

    public OrganizationSessionDirectoryController(SessionDirectoryQuery directory) {
        this.directory = directory;
    }

    @GetMapping("/sessions")
    public SessionDirectory.Listing sessions(@PathVariable UUID organizationId) {
        return directory.forOrganization(organizationId);
    }
}
