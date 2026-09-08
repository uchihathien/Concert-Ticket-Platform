// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository {

    Optional<EventRow> findById(UUID eventId);

    List<UUID> sessionIdsOf(UUID eventId);

    void markPublished(UUID eventId, Instant at);

    /**
     * Các suất diễn khác dùng cùng địa điểm, giờ giấc chồng lên nhau.
     *
     * <p>Chỉ để <b>cảnh báo</b>, không chặn: một địa điểm dùng chung phục vụ nhiều tổ chức, và hệ
     * thống không biết ai đã thuê nó ngày nào — đó là hợp đồng ngoài hệ thống. Chặn nhầm một sự
     * kiện có thật tệ hơn nhiều so với hiện một cảnh báo thừa.
     */
    List<VenueConflict> venueConflicts(UUID venueId, Instant startsAt, Instant endsAt, UUID excludeSessionId);

    record EventRow(UUID id, UUID organizationId, String slug, String title, String status) {}

    record VenueConflict(UUID eventSessionId, UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}
}
