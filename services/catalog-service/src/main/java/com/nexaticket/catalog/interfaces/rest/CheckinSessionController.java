// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.CatalogViews.CheckinSession;
import com.nexaticket.catalog.application.query.CheckinSessionQuery;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session list for staff, scoped to an organization they can check in for. */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/checkin-sessions")
public class CheckinSessionController {

    private final CheckinSessionQuery sessions;

    public CheckinSessionController(CheckinSessionQuery sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public List<CheckinSession> list(@PathVariable UUID organizationId) {
        return sessions.forOrganization(organizationId);
    }
}
