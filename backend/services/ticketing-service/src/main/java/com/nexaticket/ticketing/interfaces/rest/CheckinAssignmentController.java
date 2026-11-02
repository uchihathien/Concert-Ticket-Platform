// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.ticketing.application.CheckinAssignments;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sự kiện/suất mà CHÍNH người gọi được gán soát vé — màn đầu tiên của app Staff.
 *
 * <p>Chỉ phân công còn hiệu lực (sắp tới và đang diễn ra). Người quản lý sự kiện của tổ chức soát
 * được mọi sự kiện nên nhận {@code restricted = false} và danh sách rỗng — app hiện mọi suất của tổ
 * chức cho họ như trước.
 *
 * <p>TenantFilter đã kiểm người gọi thuộc tổ chức trên đường dẫn; người ngoài nhận 404.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/checkin-assignments")
public class CheckinAssignmentController {

    private final CheckinAssignments assignments;

    public CheckinAssignmentController(CheckinAssignments assignments) {
        this.assignments = assignments;
    }

    /**
     * @param restricted {@code false} khi người gọi quản lý được sự kiện — không bị giới hạn theo
     *     phân công
     */
    public record MyAssignments(boolean restricted, List<MyAssignment> assignments) {}

    /**
     * @param eventSessionId {@code null} khi được gán cả sự kiện
     * @param sessions các suất trong phạm vi được gán, sớm nhất trước
     * @param status {@code UPCOMING} hoặc {@code ONGOING}; {@code UNKNOWN} khi chưa đọc được lịch
     */
    public record MyAssignment(
            UUID eventId,
            UUID eventSessionId,
            String eventTitle,
            String venueName,
            Instant startsAt,
            Instant expiresAt,
            String status,
            List<SessionView> sessions) {}

    public record SessionView(UUID eventSessionId, Instant startsAt, Instant endsAt) {}

    @GetMapping("/me")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public MyAssignments mine(@PathVariable UUID organizationId) {
        TenantId tenant = TenantId.of(organizationId);
        TenantScope scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, tenant);
        if (TenantContext.has(Permission.CATALOG_MANAGE, tenant)) {
            return new MyAssignments(false, List.of());
        }
        return new MyAssignments(
                true,
                assignments.activeOf(organizationId, scope.userId().value()).stream()
                        .map(a -> new MyAssignment(
                                a.eventId(),
                                a.eventSessionId(),
                                a.eventTitle(),
                                a.venueName(),
                                a.startsAt(),
                                a.expiresAt(),
                                a.status(),
                                a.sessions().stream()
                                        .map(s -> new SessionView(s.eventSessionId(), s.startsAt(), s.endsAt()))
                                        .toList()))
                        .toList());
    }
}
