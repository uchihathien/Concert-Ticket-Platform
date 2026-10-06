// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
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
     *
     * <p>Hỏi {@link Permission#CATALOG_MANAGE} chứ không liệt kê tên vai trò: danh sách vai trò nào
     * được phép là việc của ma trận trong {@code Role}, và một service không nên có ý kiến riêng
     * về nó. Thêm vai trò thứ bảy thì chỗ này không phải sửa.
     */
    public UUID requireCatalogManager(UUID organizationId) {
        TenantContext.requirePermission(Permission.CATALOG_MANAGE, TenantId.of(organizationId));
        return organizationId;
    }
}
