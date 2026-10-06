// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.LayoutSpecs;
import com.nexaticket.catalog.application.command.ConfigureVenueZonesHandler;
import com.nexaticket.catalog.application.command.CreateEventFromTemplateHandler;
import com.nexaticket.catalog.application.media.SeatMapImageUseCase;
import com.nexaticket.catalog.application.query.AdminCatalogQuery;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.FloorPlanQuery;
import com.nexaticket.catalog.application.query.FloorPlanViews;
import com.nexaticket.catalog.application.query.TemplateQueries;
import com.nexaticket.catalog.application.query.TemplateViews;
import com.nexaticket.platform.web.error.ApiException;
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
    private final FloorPlanQuery floorPlans;
    private final SeatMapImageUseCase seatMapImages;
    private final CatalogQueries queries;

    public OrganizationSeatingController(
            CreateEventFromTemplateHandler fromTemplate,
            ConfigureVenueZonesHandler configureZones,
            TemplateQueries templateQueries,
            AdminCatalogQuery adminQuery,
            FloorPlanQuery floorPlans,
            SeatMapImageUseCase seatMapImages,
            CatalogQueries queries) {
        this.fromTemplate = fromTemplate;
        this.configureZones = configureZones;
        this.templateQueries = templateQueries;
        this.adminQuery = adminQuery;
        this.floorPlans = floorPlans;
        this.seatMapImages = seatMapImages;
        this.queries = queries;
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
                toStageSpec(request.stage()),
                request.zones().stream()
                        .map(z -> new ConfigureVenueZonesHandler.ZoneSpec(
                                z.zoneCode(),
                                z.name(),
                                z.kind(),
                                z.rowCount(),
                                z.seatsPerRow(),
                                z.capacity(),
                                z.sortOrder(),
                                toLayoutSpec(z)))
                        .toList());

        CatalogViews.AdminVenue venue = adminQuery.venues(organizationId).stream()
                .filter(v -> v.id().equals(venueId))
                .findFirst()
                .orElseThrow();

        return new ZonesResponse(
                venue, result.inserted(), result.updated(), result.removedZones(), result.removedTicketTypes());
    }

    /**
     * Mặt bằng đã giải: sân khấu, đường bao từng khu, và toạ độ từng ghế.
     *
     * <p>Có ghế ở đây, khác với bản công khai: ban tổ chức xem trước sơ đồ <b>trước khi publish</b>,
     * lúc inventory chưa dựng ghế nào. Không có toạ độ thì màn hình phải tự tính lại bằng một bản
     * sao của công thức trong {@code ZoneLayout}, viết bằng TypeScript — và nó sẽ lệch ở lần sửa
     * thứ hai, với triệu chứng là bản xem trước không giống thứ khách nhìn thấy.
     */
    /**
     * Ảnh sơ đồ khu vực ghế của địa điểm — tấm hình ban tổ chức vẫn dùng để bán vé ngoài đời.
     *
     * <p>{@code PUT} chứ không {@code PATCH}: endpoint chỉ mang một trường, nên gọi nó luôn là
     * "đặt giá trị này", và bỏ trống là gỡ ảnh. Xem {@code SeatMapImageUseCase}.
     */
    /** Ảnh sơ đồ đang gắn với sự kiện: của riêng nó, của địa điểm, và tấm khách thật sự nhìn thấy. */
    @GetMapping("/events/{eventId}/seat-map-image")
    public CatalogViews.SeatMapImages seatMapImagesOfEvent(
            @PathVariable UUID organizationId, @PathVariable UUID eventId) {
        return queries.seatMapImages(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
    }

    @PutMapping("/venues/{venueId}/seat-map-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setVenueSeatMapImage(
            @PathVariable UUID organizationId,
            @PathVariable UUID venueId,
            @Valid @RequestBody SeatMapImageRequest request) {
        seatMapImages.setForVenue(organizationId, venueId, request.imageUrl());
    }

    /** Ảnh sơ đồ riêng của một sự kiện, đè lên ảnh của địa điểm. */
    @PutMapping("/events/{eventId}/seat-map-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEventSeatMapImage(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventId,
            @Valid @RequestBody SeatMapImageRequest request) {
        seatMapImages.setForEvent(organizationId, eventId, request.imageUrl());
    }

    /**
     * @param imageUrl địa chỉ công khai nhận được từ {@code POST /uploads/poster}. Bỏ trống hoặc
     *     {@code null} để gỡ ảnh đang có.
     */
    public record SeatMapImageRequest(@Size(max = 500) String imageUrl) {}

    @GetMapping("/venues/{venueId}/floor-plan")
    public FloorPlanViews.FloorPlanView floorPlan(@PathVariable UUID organizationId, @PathVariable UUID venueId) {
        return floorPlans.forVenue(organizationId, venueId);
    }

    /**
     * Xem trước một sơ đồ <b>chưa lưu</b> — dùng bởi trình sửa sơ đồ.
     *
     * <p>Nhận đúng cùng body với {@code PUT …/zones}, nhưng <b>không ghi gì</b>. Nhờ vậy màn hình
     * sửa không phải chép công thức toạ độ sang TypeScript: nó hỏi chính phép tính sẽ chạy lúc
     * publish, nên thứ ban tổ chức nhìn thấy đúng bằng thứ được materialize.
     *
     * <p>{@code POST} cho một phép đọc, có chủ đích: đầu vào là cả tập khu, và một khán phòng vài
     * chục khu không nhét vừa query string.
     *
     * <p>Không kiểm {@code VENUE_LAYOUT_LOCKED} hay {@code VENUE_IN_USE}: xem trước không đổi gì,
     * và chặn nó sẽ làm ban tổ chức không nhìn được sơ đồ của chính địa điểm đang bán vé.
     */
    @PostMapping("/venues/{venueId}/floor-plan/preview")
    public FloorPlanViews.FloorPlanView previewFloorPlan(
            @PathVariable UUID organizationId, @PathVariable UUID venueId, @Valid @RequestBody ZonesRequest request) {

        return floorPlans.preview(
                organizationId,
                venueId,
                toStageSpec(request.stage()),
                request.zones().stream()
                        .map(OrganizationSeatingController::toDraft)
                        .toList());
    }

    /**
     * Dịch sân khấu từ hình dạng request sang hình dạng application.
     *
     * <p>Dùng chung cho lệnh lưu và lệnh xem trước: hai phép dịch khác nhau nghĩa là bản xem trước
     * không còn là bản sẽ được lưu, và đó đúng là thứ trình sửa sơ đồ tồn tại để tránh.
     */
    private static LayoutSpecs.StageSpec toStageSpec(StageRequest stage) {
        return stage == null
                ? null
                : new LayoutSpecs.StageSpec(stage.shape(), stage.x(), stage.y(), stage.width(), stage.height());
    }

    private static LayoutSpecs.ZoneLayoutSpec toLayoutSpec(ZoneRequest zone) {
        return new LayoutSpecs.ZoneLayoutSpec(
                zone.layoutShape(),
                zone.originX(),
                zone.originY(),
                zone.rotationDeg(),
                zone.innerRadius(),
                zone.startAngleDeg(),
                zone.endAngleDeg());
    }

    private static FloorPlanQuery.ZoneDraft toDraft(ZoneRequest zone) {
        return new FloorPlanQuery.ZoneDraft(
                zone.zoneCode(),
                zone.name(),
                zone.kind(),
                zone.rowCount(),
                zone.seatsPerRow(),
                zone.capacity(),
                zone.sortOrder(),
                toLayoutSpec(zone));
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

    /**
     * Sơ đồ rỗng bị từ chối: một địa điểm không có khu là một địa điểm không bán được gì.
     *
     * @param stage {@code null} đưa địa điểm về sân khấu mặc định. Đi cùng tập khu chứ không có
     *     endpoint riêng — sân khấu và khu là một mặt bằng, và toạ độ khu chỉ có nghĩa so với chỗ
     *     sân khấu đứng.
     */
    public record ZonesRequest(@Valid StageRequest stage, @NotEmpty @Valid List<ZoneRequest> zones) {}

    /** @param height bỏ qua với {@code CIRCLE} — sân khấu tròn lấy {@code width} làm đường kính */
    public record StageRequest(
            @NotNull @Pattern(regexp = "RECTANGLE|CIRCLE|THRUST") String shape,
            @NotNull Double x,
            @NotNull Double y,
            @NotNull @Positive Double width,
            @PositiveOrZero Double height) {}

    /**
     * @param layoutShape bỏ trống thì bố cục tự động xếp khu này xuống dưới sân khấu. Ba trường
     *     cung ({@code innerRadius}, {@code startAngleDeg}, {@code endAngleDeg}) chỉ đọc khi
     *     {@code layoutShape = ARC}, còn {@code rotationDeg} chỉ đọc khi {@code GRID} — ràng buộc
     *     ấy nằm ở handler chứ không ở annotation, vì nó phụ thuộc giá trị của một trường khác và
     *     Bean Validation tả kiểu phụ thuộc đó bằng một annotation tuỳ biến mà không ai đọc lại.
     */
    public record ZoneRequest(
            @NotBlank @Size(max = 16) String zoneCode,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "SEATED|STANDING") String kind,
            @Positive Integer rowCount,
            @Positive Integer seatsPerRow,
            @Positive Integer capacity,
            Integer sortOrder,
            @Pattern(regexp = "GRID|ARC") String layoutShape,
            Double originX,
            Double originY,
            Double rotationDeg,
            @PositiveOrZero Double innerRadius,
            Double startAngleDeg,
            Double endAngleDeg) {}

    /**
     * @param removedTicketTypes số hạng vé bị xoá theo khu không còn nữa. Có mặt trong phản hồi
     *     chứ không âm thầm: xoá khu "Thường" cũng xoá mức giá đã khai cho nó, và ban tổ chức phải
     *     biết điều đó ngay chứ không phải phát hiện ở bước publish.
     */
    public record ZonesResponse(
            CatalogViews.AdminVenue venue, int inserted, int updated, int removedZones, int removedTicketTypes) {}
}
