// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.SlugAllocator;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tạo sự kiện ở trạng thái nháp. */
@Service
public class CreateEventHandler {

    private final EventRepository events;
    private final VenueRepository venues;
    private final CatalogAccess access;
    private final SlugAllocator slugs;

    public CreateEventHandler(
            EventRepository events, VenueRepository venues, CatalogAccess access, SlugAllocator slugs) {
        this.events = events;
        this.venues = venues;
        this.access = access;
        this.slugs = slugs;
    }

    /** @return id của sự kiện vừa tạo; kiểu của domain không đi ra khỏi tầng application */
    @Transactional
    public UUID handle(
            UUID organizationId,
            UUID venueId,
            String title,
            String requestedSlug,
            String summary,
            String description,
            String category,
            String posterUrl) {
        access.requireCatalogManager(organizationId);

        // Địa điểm phải thuộc chính tổ chức này. Thiếu câu này thì ban tổ chức A dựng được sự kiện
        // trên địa điểm của ban tổ chức B — và tệ hơn, publish nó, sinh ra tồn kho mang mã khu của
        // người khác.
        venues.findById(organizationId, venueId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        Event event = Event.draft(
                organizationId,
                venueId,
                slugs.allocate(requestedSlug, title),
                title,
                summary,
                description,
                category,
                posterUrl);
        events.insert(event);
        return event.id();
    }
}
