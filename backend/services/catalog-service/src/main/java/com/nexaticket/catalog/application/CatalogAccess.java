// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cửa quyền của khu vực quản trị catalog.
 *
 * <p>Gom về một chỗ vì mọi lệnh ghi của catalog đều hỏi đúng một câu: người này có quản được sự
 * kiện của tổ chức đó không. Chép câu hỏi ấy vào tám handler là tạo tám chỗ để quên.
 *
 * <p>Việc "có thấy được tổ chức này không" thì {@code TenantFilter} đã trả lời rồi — nó trả 404
 * trước khi request tới được đây. Ở đây chỉ còn câu hỏi về vai trò.
 */
@Component
public class CatalogAccess {

    /**
     * Đòi vai trò quản lý sự kiện trở lên.
     *
     * <p>{@code EVENT_MANAGER} đủ để dựng và publish sự kiện — đó chính là công việc của vai trò
     * này. Những thứ động tới tiền (tài khoản ngân hàng, đối soát) mới cần {@code ORG_ADMIN}, và
     * chúng không nằm ở service này.
     */
    public UUID requireCatalogManager(UUID organizationId) {
        TenantScope scope = TenantContext.requireAuthenticated();
        if (scope.superAdmin()) {
            return organizationId;
        }
        Role role = scope.roleIn(TenantId.of(organizationId));
        if (role == null || !role.canManageCatalog()) {
            throw ApiException.forbidden("Requires EVENT_MANAGER or above");
        }
        return organizationId;
    }
}
