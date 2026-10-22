// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.query.CatalogViews.CheckinSession;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
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

    /**
     * Giờ diễn của một suất, cho lời gọi nội bộ từ ticketing-service.
     *
     * <p>Không kiểm quyền ở đây: đường này nằm dưới {@code /internal/**}, không ra khỏi mạng nội
     * bộ, và người gọi là một service chứ không phải một người dùng.
     */
    @Transactional(readOnly = true)
    public CatalogViews.SessionInfo info(UUID eventSessionId) {
        return queries.sessionInfo(eventSessionId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Session not found"));
    }

    /** Mọi suất của những sự kiện này — cho lời gọi nội bộ từ ticketing-service, không kiểm quyền. */
    @Transactional(readOnly = true)
    public List<CatalogViews.SessionInfo> ofEvents(java.util.Collection<UUID> eventIds) {
        return queries.sessionsOfEvents(eventIds);
    }
}
