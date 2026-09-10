// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.PublishBlocker;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đường đọc của khu vực quản trị, dựng từ aggregate chứ không từ SQL riêng.
 *
 * <p>Ngược với {@link CatalogQueries}, và có lý do: màn hình này cần trả lời "còn thiếu gì để
 * publish", mà câu trả lời nằm trong {@code Event.preflight}. Viết lại luật đó bằng SQL là tạo bản
 * sao thứ hai của một quy tắc nghiệp vụ — hai bản sẽ lệch nhau, và bản sai là bản người dùng nhìn
 * thấy: checklist báo xanh còn nút Publish trả 409.
 *
 * <p>Cái giá là dựng lại cả cây suất diễn cho một sự kiện. Với MỘT sự kiện thì đó là vài chục hàng
 * — đổi lấy việc chỉ có một định nghĩa của "sẵn sàng bán" thì rất đáng.
 */
@Service
public class AdminCatalogQuery {

    private final EventRepository events;
    private final VenueRepository venues;
    private final CatalogAccess access;

    public AdminCatalogQuery(EventRepository events, VenueRepository venues, CatalogAccess access) {
        this.events = events;
        this.venues = venues;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Optional<CatalogViews.AdminEventDetail> event(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);

        return events.findByIdForOrganization(organizationId, eventId).map(event -> {
            Venue venue = venues.findById(organizationId, event.venueId()).orElseThrow();
            return toDetail(event, venue);
        });
    }

    @Transactional(readOnly = true)
    public List<CatalogViews.AdminVenue> venues(UUID organizationId) {
        access.requireCatalogManager(organizationId);
        return venues.findAllByOrganization(organizationId).stream()
                .map(AdminCatalogQuery::toVenue)
                .toList();
    }

    /**
     * Dùng lại bởi {@link OrganizationDashboardQuery}: màn hình master data cần đúng hình dạng này
     * cho phần "thông tin chi tiết + khu vực + hạng vé", và dựng bản thứ hai của nó là để hai màn
     * hình cùng nói về một sự kiện bằng hai giọng khác nhau.
     */
    static CatalogViews.AdminEventDetail toDetail(Event event, Venue venue) {
        Map<UUID, VenueZone> zones =
                venue.zones().stream().collect(Collectors.toMap(VenueZone::id, Function.identity()));

        List<CatalogViews.AdminSession> sessions = event.sessions().stream()
                .map(session -> toSession(session, zones))
                .toList();

        return new CatalogViews.AdminEventDetail(
                event.id(),
                event.slug().value(),
                event.title(),
                event.summary(),
                event.description(),
                event.category(),
                event.posterUrl(),
                event.status().name(),
                event.publishedAt(),
                toVenue(venue),
                sessions,
                event.preflight(venue).stream().map(PublishBlocker::name).toList());
    }

    private static CatalogViews.AdminSession toSession(EventSession session, Map<UUID, VenueZone> zones) {
        return new CatalogViews.AdminSession(
                session.id(),
                session.startsAt(),
                session.endsAt(),
                session.salesOpenAt(),
                session.salesCloseAt(),
                session.maxSeatedPerHold(),
                session.maxStandingPerHold(),
                session.maxUnitsPerHold(),
                session.maxTicketsPerCustomer(),
                session.ticketTypes().stream()
                        .map(type -> toTicketType(type, zones.get(type.venueZoneId())))
                        .toList());
    }

    private static CatalogViews.AdminTicketType toTicketType(TicketType type, VenueZone zone) {
        // zone không bao giờ null trên dữ liệu lành lặn — khoá ngoại ticket_types.venue_zone_id
        // bảo đảm. Vẫn phòng thủ ở đây để một khu bị xoá bằng tay không làm trắng cả trang.
        return new CatalogViews.AdminTicketType(
                type.id(),
                type.venueZoneId(),
                zone == null ? "?" : zone.zoneCode(),
                zone == null ? "(khu đã bị xoá)" : zone.name(),
                type.name(),
                type.priceVnd(),
                zone == null ? 0 : zone.seatCount());
    }

    private static CatalogViews.AdminVenue toVenue(Venue venue) {
        return new CatalogViews.AdminVenue(
                venue.id(),
                venue.name(),
                venue.city(),
                venue.address(),
                venue.capacity(),
                venue.zones().stream()
                        .map(zone -> new CatalogViews.AdminZone(
                                zone.id(),
                                zone.zoneCode(),
                                zone.name(),
                                zone.kind().name(),
                                zone.rowCount(),
                                zone.seatsPerRow(),
                                zone.capacity(),
                                zone.seatCount()))
                        .toList());
    }
}
