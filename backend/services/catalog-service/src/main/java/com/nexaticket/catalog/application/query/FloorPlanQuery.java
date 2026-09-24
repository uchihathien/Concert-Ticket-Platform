// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.FloorPlan;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
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
