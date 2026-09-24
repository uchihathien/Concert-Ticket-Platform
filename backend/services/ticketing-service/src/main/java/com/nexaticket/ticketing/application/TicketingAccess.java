// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cửa quyền của khu vực quản trị ticketing.
 *
 * <p>Cùng khuôn với {@code CatalogAccess}: {@code TenantFilter} đã trả lời "có thấy được tổ chức
 * này không" và trả 404 trước khi request tới đây, nên chỗ này chỉ còn câu hỏi về vai trò.
 */
@Component
public class TicketingAccess {

    /**
     * Đòi quyền đọc số liệu vận hành của tổ chức.
     *
     * <p>Danh sách vé mang <b>tên người mua</b>, nên nó không phải dữ liệu công khai trong tổ
     * chức. {@link Permission#ORG_METRICS_VIEW} là quyền đọc đúng phạm vi ấy — cùng quyền đang
     * canh doanh thu và số vé bán.
     *
     * <p>Cố ý KHÔNG dùng {@link Permission#CHECKIN_SCAN}: nhân viên soát vé cần quét được mã QR,
     * không cần tra được danh bạ khách hàng của cả sự kiện. Hai việc đó khác nhau, và gộp chúng
     * nghĩa là mọi nhân viên thời vụ ở cửa đều xuất được danh sách khách.
     */
    public UUID requireTicketReader(UUID organizationId) {
        TenantContext.requirePermission(Permission.ORG_METRICS_VIEW, TenantId.of(organizationId));
        return organizationId;
    }
}
