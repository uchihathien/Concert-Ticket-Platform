// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.port.VenueRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tạo địa điểm. Khu vực khai riêng ở bước sau — xem {@link AddZoneHandler}. */
@Service
public class CreateVenueHandler {

    private final VenueRepository venues;
    private final CatalogAccess access;

    public CreateVenueHandler(VenueRepository venues, CatalogAccess access) {
        this.venues = venues;
        this.access = access;
    }

    /**
     * @return id của địa điểm vừa tạo
     *     <p>Trả id chứ không trả aggregate: {@code Venue} là kiểu của domain, và tầng interfaces
     *     không được chạm vào domain (ArchitectureRules.hexagonalLayers). Controller đọc lại qua
     *     đường đọc để dựng phản hồi — thêm một truy vấn, đổi lấy việc kiểu của domain không rò ra
     *     ngoài hợp đồng HTTP.
     */
    @Transactional
    public UUID handle(UUID organizationId, String name, String city, String address) {
        access.requireCatalogManager(organizationId);
        Venue venue = Venue.create(organizationId, name, city, address);
        venues.save(venue);
        return venue.id();
    }
}
