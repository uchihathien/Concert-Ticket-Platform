// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ địa điểm. */
public interface VenueRepository {

    void save(Venue venue);

    void addZone(VenueZone zone);

    /**
     * Đọc địa điểm kèm khu.
     *
     * <p>Nhận cả {@code organizationId} chứ không chỉ {@code id}: lọc theo tổ chức ngay trong câu
     * truy vấn biến IDOR thành "không tìm thấy" thay vì thành một bước kiểm tra mà ai đó sẽ quên.
     */
    Optional<Venue> findById(UUID organizationId, UUID venueId);

    List<Venue> findAllByOrganization(UUID organizationId);
}
