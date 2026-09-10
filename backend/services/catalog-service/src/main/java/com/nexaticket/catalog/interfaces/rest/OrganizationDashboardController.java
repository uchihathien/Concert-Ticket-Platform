// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.DashboardViews;
import com.nexaticket.catalog.application.query.OrganizationDashboardQuery;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bảng điều khiển của tổ chức.
 *
 * <p>Hai endpoint, hai câu hỏi: "tôi đang có những sự kiện nào" và "sự kiện này đang ra sao". Cái
 * thứ hai ghép dữ liệu của ba service — xem {@code OrganizationDashboardQuery} để biết vì sao việc
 * ghép nằm ở backend chứ không ở frontend.
 *
 * <p>Đường dẫn mang {@code /organizations/{id}} nên {@code TenantFilter} kiểm membership và trả 404
 * trước khi request tới được đây; kiểm vai trò thì nằm trong query.
 *
 * <p><b>Không có endpoint xuất file.</b> Master data trả về JSON, và việc biến nó thành CSV hay
 * Excel là việc của frontend: nó biết ngôn ngữ, múi giờ và định dạng số mà người dùng đang xem,
 * còn ở đây thì không. Thêm một đường sinh file ở backend là thêm một chỗ để ngày tháng hiện sai
 * định dạng.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}")
public class OrganizationDashboardController {

    private final OrganizationDashboardQuery dashboard;

    public OrganizationDashboardController(OrganizationDashboardQuery dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    public DashboardViews.OrganizationDashboard overview(@PathVariable UUID organizationId) {
        return dashboard.overview(organizationId);
    }

    /**
     * Master data đầy đủ của một sự kiện: chi tiết, khu vực, trạng thái chỗ theo khu, số vé bán và
     * doanh thu theo từng suất.
     *
     * <p>Trả 200 kèm {@code degraded} khi một service phía sau im lặng, không trả 5xx: phần quan
     * trọng nhất của màn hình nằm ngay trong database của service này, và mất tất cả để khỏi mất
     * một phần là đánh đổi ngược.
     */
    @GetMapping("/events/{eventId}/master-data")
    public DashboardViews.EventMasterData masterData(@PathVariable UUID organizationId, @PathVariable UUID eventId) {
        return dashboard.masterData(organizationId, eventId);
    }
}
