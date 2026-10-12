// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.ticketing.application.CheckinAccess;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.application.query.CheckinHistoryQuery;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Organization-scoped check-in route for staff assigned to more than one organization. */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/sessions/{eventSessionId}/checkins")
public class OrganizationCheckinController {

    private final CheckInHandler checkIn;
    private final CheckinHistoryQuery history;

    private final CheckinAccess access;

    public OrganizationCheckinController(CheckInHandler checkIn, CheckinHistoryQuery history, CheckinAccess access) {
        this.checkIn = checkIn;
        this.history = history;
        this.access = access;
    }

    @PostMapping
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public CheckinController.ScanResponse scan(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @Valid @RequestBody CheckinController.ScanRequest request) {
        return CheckinController.scan(checkIn, access, TenantId.of(organizationId), eventSessionId, request);
    }

    /**
     * Lịch sử quét và số khách của suất đang soát.
     *
     * <p>Cùng đường dẫn với lượt quét, khác phương thức: api-gateway định tuyến theo đường dẫn, nên
     * không phải khai thêm route nào.
     */
    @GetMapping
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public CheckinHistoryQuery.History history(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Lịch sử mang tên khách: cùng chốt quyền với lượt quét, không lỏng hơn.
        access.requireScanner(TenantId.of(organizationId), eventSessionId);
        return history.forSession(organizationId, eventSessionId, query, page, size);
    }
}
