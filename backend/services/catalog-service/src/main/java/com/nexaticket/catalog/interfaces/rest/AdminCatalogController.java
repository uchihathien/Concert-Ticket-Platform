// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.command.AddSessionHandler;
import com.nexaticket.catalog.application.command.AddTicketTypeHandler;
import com.nexaticket.catalog.application.command.AddZoneHandler;
import com.nexaticket.catalog.application.command.CreateEventHandler;
import com.nexaticket.catalog.application.command.CreateVenueHandler;
import com.nexaticket.catalog.application.command.PublishEventHandler;
import com.nexaticket.catalog.application.command.UnpublishEventHandler;
import com.nexaticket.catalog.application.command.UpdateEventHandler;
import com.nexaticket.catalog.application.query.AdminCatalogQuery;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.platform.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khu vực quản trị của ban tổ chức.
 *
 * <p><b>Đường dẫn khác với docs/02-catalog-admin/api.md</b>, và đây là thay đổi có chủ đích. Docs
 * viết {@code /v1/admin/events}; ở đây là {@code /v1/organizations/{organizationId}/events}. Lý do
 * nằm ở {@code TenantFilter}: nó lấy tổ chức từ đoạn {@code /organizations/{id}} trên đường dẫn và
 * trả 404 nếu người gọi không phải thành viên. Với {@code /v1/admin/events} thì không có tổ chức
 * nào trên đường dẫn, và filter rơi về "nếu người này chỉ thuộc đúng một tổ chức thì lấy tổ chức
 * đó" — im lặng đúng với phần lớn người dùng, và im lặng SAI với người thuộc hai tổ chức: họ sẽ tạo
 * sự kiện cho tổ chức mà họ không định chọn.
 *
 * <p>Controller chỉ phụ thuộc tầng application (ArchitectureRules.hexagonalLayers). Kiểm tra vai
 * trò nằm trong handler chứ không ở đây — vì lệnh còn được gọi từ chỗ khác ngoài HTTP (bộ dựng dữ
 * liệu mẫu), và một cửa quyền chỉ đóng ở tầng web là một cửa quyền đi vòng được.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}")
public class AdminCatalogController {

    private final CreateVenueHandler createVenue;
    private final AddZoneHandler addZone;
    private final CreateEventHandler createEvent;
    private final UpdateEventHandler updateEvent;
    private final AddSessionHandler addSession;
    private final AddTicketTypeHandler addTicketType;
    private final PublishEventHandler publishEvent;
    private final UnpublishEventHandler unpublishEvent;
    private final AdminCatalogQuery adminQuery;
    private final CatalogQueries queries;

    public AdminCatalogController(
            CreateVenueHandler createVenue,
            AddZoneHandler addZone,
            CreateEventHandler createEvent,
            UpdateEventHandler updateEvent,
            AddSessionHandler addSession,
            AddTicketTypeHandler addTicketType,
            PublishEventHandler publishEvent,
            UnpublishEventHandler unpublishEvent,
            AdminCatalogQuery adminQuery,
            CatalogQueries queries) {
        this.createVenue = createVenue;
        this.addZone = addZone;
        this.createEvent = createEvent;
        this.updateEvent = updateEvent;
        this.addSession = addSession;
        this.addTicketType = addTicketType;
        this.publishEvent = publishEvent;
        this.unpublishEvent = unpublishEvent;
        this.adminQuery = adminQuery;
        this.queries = queries;
    }

    // --- Địa điểm ----------------------------------------------------------

    @GetMapping("/venues")
    public List<CatalogViews.AdminVenue> venues(@PathVariable UUID organizationId) {
        return adminQuery.venues(organizationId);
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.AdminVenue createVenue(
            @PathVariable UUID organizationId, @Valid @RequestBody VenueRequest request) {
        UUID id = createVenue.handle(organizationId, request.name(), request.city(), request.address());
        return adminQuery.venues(organizationId).stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @PostMapping("/venues/{venueId}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.AdminZone createZone(
            @PathVariable UUID organizationId, @PathVariable UUID venueId, @Valid @RequestBody ZoneRequest request) {
        return addZone.handle(
                organizationId,
                venueId,
                request.zoneCode(),
                request.name(),
                request.kind(),
                request.rowCount(),
                request.seatsPerRow(),
                request.capacity(),
                request.sortOrder() == null ? 0 : request.sortOrder());
    }

    // --- Sự kiện -----------------------------------------------------------

    @GetMapping("/events")
    public List<CatalogViews.AdminEventRow> events(@PathVariable UUID organizationId) {
        // Kiểm quyền qua đường đọc của aggregate: cả hai đều gọi CatalogAccess, nên không có
        // đường nào vào được bảng sự kiện mà không qua kiểm tra vai trò.
        adminQuery.venues(organizationId);
        return queries.organizationEvents(organizationId);
    }

    @GetMapping("/events/{eventId}")
    public CatalogViews.AdminEventDetail event(@PathVariable UUID organizationId, @PathVariable UUID eventId) {
        return adminQuery
                .event(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.AdminEventDetail createEvent(
            @PathVariable UUID organizationId, @Valid @RequestBody EventRequest request) {
        UUID id = createEvent.handle(
                organizationId,
                request.venueId(),
                request.title(),
                request.slug(),
                request.summary(),
                request.description(),
                request.category(),
                request.posterUrl());
        return adminQuery.event(organizationId, id).orElseThrow();
    }

    @PatchMapping("/events/{eventId}")
    public CatalogViews.AdminEventDetail updateEvent(
            @PathVariable UUID organizationId, @PathVariable UUID eventId, @RequestBody EventPatch request) {
        updateEvent.handle(
                organizationId,
                eventId,
                request.title(),
                request.summary(),
                request.description(),
                request.category(),
                request.posterUrl());
        return adminQuery.event(organizationId, eventId).orElseThrow();
    }

    @PostMapping("/events/{eventId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.AdminEventDetail createSession(
            @PathVariable UUID organizationId, @PathVariable UUID eventId, @Valid @RequestBody SessionRequest request) {
        addSession.handle(
                organizationId,
                eventId,
                request.startsAt(),
                request.endsAt(),
                request.salesOpenAt(),
                request.salesCloseAt(),
                request.maxSeatedPerHold(),
                request.maxStandingPerHold(),
                request.maxUnitsPerHold(),
                request.maxTicketsPerCustomer());
        return adminQuery.event(organizationId, eventId).orElseThrow();
    }

    @PostMapping("/events/{eventId}/sessions/{sessionId}/ticket-types")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.AdminEventDetail createTicketType(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody TicketTypeRequest request) {
        addTicketType.handle(
                organizationId,
                eventId,
                sessionId,
                request.venueZoneId(),
                request.name(),
                request.priceVnd(),
                request.sortOrder() == null ? 0 : request.sortOrder());
        return adminQuery.event(organizationId, eventId).orElseThrow();
    }

    @PostMapping("/events/{eventId}/publish")
    public CatalogViews.AdminEventDetail publish(@PathVariable UUID organizationId, @PathVariable UUID eventId) {
        publishEvent.handle(organizationId, eventId);
        return adminQuery.event(organizationId, eventId).orElseThrow();
    }

    @PostMapping("/events/{eventId}/unpublish")
    public CatalogViews.AdminEventDetail unpublish(@PathVariable UUID organizationId, @PathVariable UUID eventId) {
        unpublishEvent.handle(organizationId, eventId);
        return adminQuery.event(organizationId, eventId).orElseThrow();
    }

    // --- Hình dạng request -------------------------------------------------

    public record VenueRequest(
            @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 100) String city, String address) {}

    /**
     * Hình dạng khu không kiểm chéo ở đây bằng annotation.
     *
     * <p>Luật "khu ngồi phải có số hàng, khu đứng phải có sức chứa" nằm trong constructor của
     * {@code VenueZone} và trong ràng buộc {@code ck_zone_shape} của database. Thêm bản sao thứ ba
     * bằng annotation là ba chỗ để lệch nhau; hai chỗ kia thì không đi vòng được.
     */
    public record ZoneRequest(
            @NotBlank @Size(max = 16) String zoneCode,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "SEATED|STANDING") String kind,
            @Positive Integer rowCount,
            @Positive Integer seatsPerRow,
            @Positive Integer capacity,
            Integer sortOrder) {}

    public record EventRequest(
            @NotNull UUID venueId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 96) String slug,
            @Size(max = 500) String summary,
            String description,
            @NotBlank @Size(max = 50) String category,
            @Size(max = 500) String posterUrl) {}

    /** Mọi trường đều tuỳ chọn: null nghĩa là "giữ nguyên", không phải "xoá đi". */
    public record EventPatch(String title, String summary, String description, String category, String posterUrl) {}

    public record SessionRequest(
            @NotNull Instant startsAt,
            Instant endsAt,
            @NotNull Instant salesOpenAt,
            @NotNull Instant salesCloseAt,
            @Positive Integer maxSeatedPerHold,
            @Positive Integer maxStandingPerHold,
            @Positive Integer maxUnitsPerHold,
            @Positive Integer maxTicketsPerCustomer) {}

    public record TicketTypeRequest(
            @NotNull UUID venueZoneId,
            @NotBlank @Size(max = 100) String name,
            @PositiveOrZero long priceVnd,
            Integer sortOrder) {}
}
