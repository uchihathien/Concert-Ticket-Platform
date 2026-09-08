// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thêm suất diễn vào sự kiện.
 *
 * <p>Chỉ thêm được khi sự kiện chưa bán. Thêm suất vào sự kiện đang bán thì suất mới sẽ không có
 * tồn kho — vì tồn kho chỉ được dựng ở lúc publish — và khách sẽ thấy một suất bấm vào là lỗi.
 * Muốn thêm suất thì rút xuống, thêm, rồi publish lại; publish lại là idempotent với suất cũ.
 */
@Service
public class AddSessionHandler {

    private final EventRepository events;
    private final CatalogAccess access;

    public AddSessionHandler(EventRepository events, CatalogAccess access) {
        this.events = events;
        this.access = access;
    }

    /** @return id của suất diễn vừa thêm */
    @Transactional
    public UUID handle(
            UUID organizationId,
            UUID eventId,
            Instant startsAt,
            Instant endsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        if (event.status().isVisibleToPublic()) {
            throw new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "Rút sự kiện xuống trước khi thêm suất diễn");
        }

        EventSession session = EventSession.create(
                event.id(),
                startsAt,
                endsAt,
                salesOpenAt,
                salesCloseAt,
                maxSeatedPerHold,
                maxStandingPerHold,
                maxUnitsPerHold,
                maxTicketsPerCustomer);
        events.addSession(session);
        return session.id();
    }
}
