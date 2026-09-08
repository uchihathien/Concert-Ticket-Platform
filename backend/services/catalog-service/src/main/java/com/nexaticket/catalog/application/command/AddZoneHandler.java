// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thêm một khu vào địa điểm.
 *
 * <p>Không có bước "kích hoạt sơ đồ chỗ" như docs/02-catalog-admin/flows.md mô tả: sơ đồ ở đây là
 * danh sách khu của địa điểm, và nó luôn ở trạng thái dùng được. Trạng thái DRAFT/ACTIVE/ARCHIVED
 * của sơ đồ chỉ cần thiết khi có một trình vẽ ghế mà người dùng phải làm dở nhiều buổi — thứ mà mô
 * hình khu hình chữ nhật đã làm biến mất.
 */
@Service
public class AddZoneHandler {

    private final VenueRepository venues;
    private final CatalogAccess access;

    public AddZoneHandler(VenueRepository venues, CatalogAccess access) {
        this.venues = venues;
        this.access = access;
    }

    /**
     * @param kind {@code SEATED} hoặc {@code STANDING}, dạng chuỗi
     *     <p>Nhận chuỗi rồi tự chuyển, chứ không để controller nhận thẳng enum của domain: tầng
     *     interfaces không được chạm vào domain. Giá trị lạ đã bị {@code @Pattern} ở request chặn
     *     từ trước, nên nhánh lỗi ở đây chỉ là lưới an toàn cho những lời gọi không qua HTTP.
     */
    @Transactional
    public CatalogViews.AdminZone handle(
            UUID organizationId,
            UUID venueId,
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int sortOrder) {
        access.requireCatalogManager(organizationId);

        Venue venue = venues.findById(organizationId, venueId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        AdmissionKind admissionKind;
        try {
            admissionKind = AdmissionKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new ApiException(CatalogErrorCode.ZONE_NOT_FOUND, "Loại khu không hợp lệ: " + kind);
        }

        VenueZone zone = new VenueZone(
                UUID.randomUUID(),
                venue.id(),
                zoneCode,
                name,
                admissionKind,
                rowCount,
                seatsPerRow,
                capacity,
                sortOrder);
        venues.addZone(zone);

        return new CatalogViews.AdminZone(
                zone.id(),
                zone.zoneCode(),
                zone.name(),
                zone.kind().name(),
                zone.rowCount(),
                zone.seatsPerRow(),
                zone.capacity(),
                zone.seatCount());
    }
}
