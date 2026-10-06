// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Organization-scoped check-in route for staff assigned to more than one organization. */
@RestController
public class OrganizationCheckinController {

    private final CheckInHandler checkIn;

    public OrganizationCheckinController(CheckInHandler checkIn) {
        this.checkIn = checkIn;
    }

    @PostMapping("/v1/organizations/{organizationId}/sessions/{eventSessionId}/checkins")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public CheckinController.ScanResponse scan(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @Valid @RequestBody CheckinController.ScanRequest request) {
        var scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, TenantId.of(organizationId));
        var result = checkIn.handle(new CheckInHandler.Command(
                request.qrToken(), eventSessionId, scope.userId().value(), organizationId, request.deviceId()));
        return new CheckinController.ScanResponse(
                result.result(), result.seatCode(), result.seatLabel(), result.ticketTypeName(), result.note());
    }
}
