// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.query.CatalogViews.CheckinSession;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model for staff selecting an event session to check in. */
@Service
public class CheckinSessionQuery {

    private final CatalogQueries queries;

    public CheckinSessionQuery(CatalogQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public List<CheckinSession> forOrganization(UUID organizationId) {
        TenantContext.requirePermission(Permission.CHECKIN_SCAN, TenantId.of(organizationId));
        return queries.checkinSessions(organizationId);
    }
}
