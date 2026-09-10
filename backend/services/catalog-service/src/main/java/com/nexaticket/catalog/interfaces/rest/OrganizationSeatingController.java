// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.command.ConfigureVenueZonesHandler;
import com.nexaticket.catalog.application.command.CreateEventFromTemplateHandler;
import com.nexaticket.catalog.application.query.AdminCatalogQuery;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.TemplateQueries;
import com.nexaticket.catalog.application.query.TemplateViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hai cách dựng sơ đồ chỗ cho một sự kiện, đứng cạnh nhau vì chúng là hai lựa chọn của cùng một
 * quyết định.
 *
 * <ol>
 *   <li><b>Chọn khung của Tổng công ty</b> — {@code POST /events/from-template}. Kết cấu khán
 *       phòng chép từ khung và trở thành cố định; tổ chức chỉ điền phần của mình.
 *   <li><b>Tự dựng</b> — {@code PUT /venues/{id}/zones}. Ví dụ ba khu VIP / Thường / Khác với số
 *       ghế tự chọn.
 * </ol>
 *
 * <p>Một địa điểm thuộc đúng một trong hai loại, và {@code VENUE_LAYOUT_LOCKED} là chỗ ranh giới đó
 * được thực thi. Nửa cố định nửa tuỳ chỉnh là thứ không giải thích được cho người dùng: "khu nào
 * sửa được" sẽ thành một câu hỏi phải tra từng dòng.
 *
 * <p>Tách khỏi {@code AdminCatalogController} vì đó là màn hình sửa từng thứ một, còn đây là hai
 * lệnh dựng cả cụm. Đường dẫn vẫn mang {@code /organizations/{id}} để {@code TenantFilter} kiểm
 * membership — xem ghi chú ở {@code AdminCatalogController}.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}")
public class OrganizationSeatingController {

    private final CreateEventFromTemplateHandler fromTemplate;
    private final ConfigureVenueZonesHandler configureZones;
    private final TemplateQueries templateQueries;
    private final AdminCatalogQuery adminQuery;

    public OrganizationSeatingController(
            CreateEventFromTemplateHandler fromTemplate,
            ConfigureVenueZonesHandler configureZones,
            TemplateQueries templateQueries,
            AdminCatalogQuery adminQuery) {
        this.fromTemplate = fromTemplate;
        this.configureZones = configureZones;
        this.templateQueries = templateQueries;
        this.adminQuery = adminQuery;
    }

    // --- Khung của Tổng công ty --------------------------------------------

    /** Chỉ khung ACTIVE. Khung nháp của nền tảng là thứ không tồn tại với ban tổ chức. */
    @GetMapping("/concert-templates")
    public List<TemplateViews.TemplateRow> templates(@PathVariable UUID organizationId) {
        return templateQueries.usableBy(organizationId);
    }

    @GetMapping("/concert-templates/{templateId}")
    public TemplateViews.TemplateDetail template(@PathVariable UUID organizationId, @PathVariable UUID templateId) {
        return templateQueries.usableById(organizationId, templateId);
    }

    /**
     * Dựng trọn sự kiện từ khung: địa điểm + khu + sự kiện + suất diễn + hạng vé, một transaction.
     *
     * <p>Trả về ở trạng thái nháp. Publish vẫn là một lệnh riêng, có checklist riêng — dựng nhanh
     * không có nghĩa là bán ngay.
     */
    @PostMapping("/events/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.AdminEventDetail createFromTemplate(
            @PathVariable UUID organizationId, @Valid @RequestBody FromTemplateRequest request) {
        UUID eventId = fromTemplate.handle(
                organizationId,
                new CreateEventFromTemplateHandler.Command(
                        request.templateId(),
                        request.title(),
                        request.slug(),
                        request.summary(),
                        request.description(),
                        request.category(),
                        request.posterUrl(),
                        request.venueName(),
                        request.city(),
                        request.address(),
                        request.startsAt(),
                        request.endsAt(),
                        request.salesOpenAt(),
                        request.salesCloseAt(),
                        request.maxSeatedPerHold(),
                        request.maxStandingPerHold(),
                        request.maxUnitsPerHold(),
                        request.maxTicketsPerCustomer(),
                        request.zonePrices()));
        return adminQuery.event(organizationId, eventId).orElseThrow();
    }

    // --- Sơ đồ tự dựng ------------------------------------------------------

    /**
     * Thay cả sơ đồ khu của một địa điểm trong một lần.
     *
     * <p>{@code PUT} vì đây là phép thay thế toàn bộ. Khu giữ nguyên {@code zoneCode} thì giữ
     * nguyên id, nên sửa "VIP: 100 → 150 ghế" không làm mất mức giá đã khai cho khu VIP.
     */
    @PutMapping("/venues/{venueId}/zones")
    public ZonesResponse configureZones(
            @PathVariable UUID organizationId, @PathVariable UUID venueId, @Valid @RequestBody ZonesRequest request) {
        ConfigureVenueZonesHandler.Result result = configureZones.handle(
                organizationId,
                venueId,
                request.zones().stream()
                        .map(z -> new ConfigureVenueZonesHandler.ZoneSpec(
                                z.zoneCode(),
                                z.name(),
                                z.kind(),
                                z.rowCount(),
                                z.seatsPerRow(),
                                z.capacity(),
                                z.sortOrder()))
                        .toList());

        CatalogViews.AdminVenue venue = adminQuery.venues(organizationId).stream()
                .filter(v -> v.id().equals(venueId))
                .findFirst()
                .orElseThrow();

        return new ZonesResponse(
                venue, result.inserted(), result.updated(), result.removedZones(), result.removedTicketTypes());
    }

    // --- Hình dạng request --------------------------------------------------

    /**
     * @param zonePrices giá theo mã khu. Khu vắng mặt ở đây thì lấy giá gợi ý của khung; khung
     *     không gợi ý mà request cũng không khai thì trả {@code ZONE_PRICE_REQUIRED} kèm danh sách
     *     mã khu còn thiếu — form điền được ngay chỗ thiếu thay vì phải dò.
     */
    public record FromTemplateRequest(
            @NotNull UUID templateId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 96) String slug,
            @Size(max = 500) String summary,
            String description,
            @Size(max = 50) String category,
            @Size(max = 500) String posterUrl,
            @NotBlank @Size(max = 200) String venueName,
            @NotBlank @Size(max = 100) String city,
            String address,
            @NotNull Instant startsAt,
            Instant endsAt,
            @NotNull Instant salesOpenAt,
            @NotNull Instant salesCloseAt,
            @Positive Integer maxSeatedPerHold,
            @Positive Integer maxStandingPerHold,
            @Positive Integer maxUnitsPerHold,
            @Positive Integer maxTicketsPerCustomer,
            Map<String, @PositiveOrZero Long> zonePrices) {}

    /** Sơ đồ rỗng bị từ chối: một địa điểm không có khu là một địa điểm không bán được gì. */
    public record ZonesRequest(@NotEmpty @Valid List<ZoneRequest> zones) {}

    public record ZoneRequest(
            @NotBlank @Size(max = 16) String zoneCode,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "SEATED|STANDING") String kind,
            @Positive Integer rowCount,
            @Positive Integer seatsPerRow,
            @Positive Integer capacity,
            Integer sortOrder) {}

    /**
     * @param removedTicketTypes số hạng vé bị xoá theo khu không còn nữa. Có mặt trong phản hồi
     *     chứ không âm thầm: xoá khu "Thường" cũng xoá mức giá đã khai cho nó, và ban tổ chức phải
     *     biết điều đó ngay chứ không phải phát hiện ở bước publish.
     */
    public record ZonesResponse(
            CatalogViews.AdminVenue venue, int inserted, int updated, int removedZones, int removedTicketTypes) {}
}
