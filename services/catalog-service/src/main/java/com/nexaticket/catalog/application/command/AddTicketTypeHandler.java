// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Khai giá cho một khu, ở một suất diễn. */
@Service
public class AddTicketTypeHandler {

    private final EventRepository events;
    private final VenueRepository venues;
    private final CatalogAccess access;

    public AddTicketTypeHandler(EventRepository events, VenueRepository venues, CatalogAccess access) {
        this.events = events;
        this.venues = venues;
        this.access = access;
    }

    @Transactional
    public void handle(
            UUID organizationId, UUID eventId, UUID sessionId, UUID zoneId, String name, long priceVnd, int sortOrder) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        if (event.status().isVisibleToPublic()) {
            throw new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "Rút sự kiện xuống trước khi đổi giá vé");
        }

        EventSession session = event.sessions().stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Session not found"));

        // Khu phải thuộc đúng địa điểm của sự kiện này. Nếu không, publish sẽ dựng tồn kho với mã
        // chỗ của một địa điểm khác — dữ liệu trông hợp lệ ở mọi bảng nhưng vé bán ra vô nghĩa.
        Venue venue = venues.findById(organizationId, event.venueId())
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));
        VenueZone zone = venue.zones().stream()
                .filter(z -> z.id().equals(zoneId))
                .findFirst()
                .orElseThrow(() -> new ApiException(CatalogErrorCode.ZONE_NOT_FOUND, "Zone not found"));

        TicketType type = TicketType.create(session.id(), zone.id(), name, priceVnd, sortOrder);
        try {
            events.addTicketType(type);
        } catch (DuplicateKeyException e) {
            // uq_type_zone. Bắt ở đây để trả 409 có nghĩa thay vì 500 — người dùng đang khai giá
            // lần thứ hai cho cùng một khu, đó là nhầm lẫn bình thường chứ không phải lỗi hệ thống.
            throw new ApiException(CatalogErrorCode.ZONE_ALREADY_PRICED, "Khu này đã có hạng vé ở suất diễn đó");
        }
    }
}
