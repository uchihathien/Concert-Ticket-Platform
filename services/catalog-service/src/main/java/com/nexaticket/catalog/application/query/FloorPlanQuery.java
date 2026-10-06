// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.LayoutSpecs;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.FloorPlan;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mặt bằng đã giải, cho hai người xem khác nhau.
 *
 * <p>Ban tổ chức xem <b>trước</b> khi publish — lúc inventory chưa có ghế nào — nên bản của họ
 * mang theo toạ độ từng ghế. Khách xem <b>trong</b> lúc bán, và sơ đồ tồn kho họ vừa tải đã có toạ
 * độ của từng ghế kèm trạng thái còn/hết; bản của khách vì thế chỉ có sân khấu và đường bao khu.
 *
 * <p>Cả hai đi qua đúng một phép tính ({@link FloorPlan}). Hai đường dựng riêng sẽ lệch nhau, và
 * triệu chứng là bản xem trước của ban tổ chức không giống thứ khách nhìn thấy — sai lệch tệ nhất
 * có thể có ở một màn hình bán vé.
 */
@Service
public class FloorPlanQuery {

    private final VenueRepository venues;
    private final CatalogAccess access;

    public FloorPlanQuery(VenueRepository venues, CatalogAccess access) {
        this.venues = venues;
        this.access = access;
    }

    /** Bản của ban tổ chức: có toạ độ từng ghế, để xem trước khi publish. */
    @Transactional(readOnly = true)
    public FloorPlanViews.FloorPlanView forVenue(UUID organizationId, UUID venueId) {
        access.requireCatalogManager(organizationId);

        Venue venue = venues.findById(organizationId, venueId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        return FloorPlanViews.FloorPlanView.of(venue.id(), venue.name(), venue.floorPlan());
    }

    /**
     * Xem trước một sơ đồ <b>chưa lưu</b>.
     *
     * <h3>Vì sao phải có endpoint này thay vì để frontend tự tính</h3>
     *
     * <p>Trình sửa sơ đồ cần thấy kết quả của thay đổi ngay khi người dùng kéo một khu — trước khi
     * bấm lưu. Cách rẻ nhất là chép công thức trong {@code ZoneLayout} sang TypeScript, và đó đúng
     * là thứ {@code plan/frontend.md} đã cấm: hai bản cài đặt của cùng một phép tính sẽ lệch nhau,
     * và bản lệch là bản ban tổ chức nhìn thấy lúc quyết định.
     *
     * <p>Nên phép tính ở lại một chỗ, và màn hình hỏi nó. Đắt hơn một lời gọi mạng, đổi lại thứ
     * ban tổ chức xem trước <b>đúng bằng</b> thứ sẽ được materialize.
     *
     * <h3>Không ghi gì</h3>
     *
     * <p>Dựng một {@link Venue} trong bộ nhớ rồi giải mặt bằng của nó. Không chạm repository, nên
     * không có đường nào để một lần xem trước lỡ tay lưu đè sơ đồ thật.
     *
     * <p>Cố ý <b>không</b> kiểm {@code VENUE_LAYOUT_LOCKED} hay {@code VENUE_IN_USE} như lệnh lưu:
     * xem trước một sơ đồ không đổi gì cả, và chặn nó sẽ làm ban tổ chức không nhìn được sơ đồ
     * của chính địa điểm đang bán vé. Hai cửa chặn ấy nằm ở {@code ConfigureVenueZonesHandler},
     * đúng chỗ có hậu quả.
     */
    @Transactional(readOnly = true)
    public FloorPlanViews.FloorPlanView preview(
            UUID organizationId, UUID venueId, LayoutSpecs.StageSpec stage, List<ZoneDraft> zones) {
        access.requireCatalogManager(organizationId);

        Venue saved = venues.findById(organizationId, venueId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        List<VenueZone> drafted = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            ZoneDraft draft = zones.get(i);
            drafted.add(new VenueZone(
                    // Id ngẫu nhiên: bản nháp có thể chứa khu chưa tồn tại, và không id nào ở đây
                    // được dùng để ghi.
                    UUID.randomUUID(),
                    venueId,
                    draft.zoneCode(),
                    draft.name(),
                    parseKind(draft.kind()),
                    draft.rowCount(),
                    draft.seatsPerRow(),
                    draft.capacity(),
                    draft.sortOrder() == null ? i : draft.sortOrder(),
                    null,
                    LayoutSpecs.toLayout(draft.layout())));
        }

        Venue transient_ = new Venue(
                saved.id(),
                saved.organizationId(),
                saved.name(),
                saved.city(),
                saved.address(),
                drafted,
                saved.sourceTemplateId(),
                LayoutSpecs.toStage(stage));

        return FloorPlanViews.FloorPlanView.of(transient_.id(), transient_.name(), transient_.floorPlan());
    }

    /** Hình dạng một khu trong bản nháp. Cùng hình dạng với {@code ConfigureVenueZonesHandler.ZoneSpec}. */
    public record ZoneDraft(
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            Integer sortOrder,
            LayoutSpecs.ZoneLayoutSpec layout) {}

    private static AdmissionKind parseKind(String kind) {
        try {
            return AdmissionKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(CatalogErrorCode.ZONE_NOT_FOUND, "Loại khu không hợp lệ: " + kind);
        }
    }

    /**
     * Bản của khách: sân khấu và đường bao khu, không có ghế.
     *
     * <p>Chỉ giải ra được với sự kiện đã publish — điều kiện ấy nằm trong câu truy vấn (xem
     * {@code VenueRepository.findByPublishedEventSlug}), nên sơ đồ của sự kiện còn nháp trả 404 chứ
     * không trả một bản rỗng.
     */
    @Transactional(readOnly = true)
    public FloorPlanViews.FloorPlanView forPublishedEvent(String slug) {
        Venue venue = venues.findByPublishedEventSlug(slug)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));

        FloorPlan plan = venue.floorPlan();
        FloorPlan withoutSeats = new FloorPlan(
                plan.stage(),
                plan.zones().stream().map(FloorPlan.PlannedZone::withoutSeats).toList(),
                plan.bounds());

        return FloorPlanViews.FloorPlanView.of(venue.id(), venue.name(), withoutSeats);
    }
}
