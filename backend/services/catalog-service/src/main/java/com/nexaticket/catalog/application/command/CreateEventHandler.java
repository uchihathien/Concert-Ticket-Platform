// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tạo sự kiện ở trạng thái nháp. */
@Service
public class CreateEventHandler {

    /** Đủ để vượt qua trùng lặp thật; nhiều hơn nữa thì slug đã hết là tên và thành mã số. */
    private static final int MAX_SLUG_ATTEMPTS = 20;

    private final EventRepository events;
    private final VenueRepository venues;
    private final CatalogAccess access;

    public CreateEventHandler(EventRepository events, VenueRepository venues, CatalogAccess access) {
        this.events = events;
        this.venues = venues;
        this.access = access;
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
                uniqueSlug(requestedSlug, title),
                title,
                summary,
                description,
                category,
                posterUrl);
        events.insert(event);
        return event.id();
    }

    /**
     * Slug do người dùng chọn thì phải đúng như đã chọn; slug sinh từ tiêu đề thì được thêm hậu tố.
     *
     * <p>Phân biệt này quan trọng: người gõ tay "hoa-am-2026" mà nhận về "hoa-am-2026-3" sẽ không
     * hiểu chuyện gì xảy ra và sẽ dán nhầm link. Còn người không quan tâm tới slug thì cũng không
     * quan tâm tới hậu tố.
     *
     * <p>Vòng lặp này là tiện lợi, không phải chốt chặn — hai request đồng thời vẫn có thể cùng
     * thấy slug trống. Chốt chặn thật là ràng buộc UNIQUE của database.
     */
    private Slug uniqueSlug(String requested, String title) {
        if (requested != null && !requested.isBlank()) {
            Slug slug = new Slug(requested.trim());
            if (events.slugExists(slug)) {
                throw new ApiException(CatalogErrorCode.SLUG_ALREADY_TAKEN, "Slug đã có người dùng: " + slug);
            }
            return slug;
        }

        Slug base = Slug.from(title);
        Slug candidate = base;
        for (int n = 2; events.slugExists(candidate); n++) {
            if (n > MAX_SLUG_ATTEMPTS) {
                throw new ApiException(CatalogErrorCode.SLUG_ALREADY_TAKEN, "Không sinh được slug trống từ: " + title);
            }
            candidate = base.withSuffix(n);
        }
        return candidate;
    }
}
