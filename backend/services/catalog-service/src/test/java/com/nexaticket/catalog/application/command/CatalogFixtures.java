// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Dữ liệu dựng sẵn cho test của tầng application — không Spring, không database.
 *
 * <p>Mọi mốc thời gian neo vào {@link #NOW} cố định, để không ca nào đỏ chỉ vì chạy vào lúc nửa
 * đêm hay vào ngày khác.
 */
final class CatalogFixtures {

    static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    static final UUID OTHER_ORG = UUID.fromString("22222222-2222-4222-a222-222222222222");

    private CatalogFixtures() {}

    static Venue venueWithZones(UUID venueId, VenueZone... zones) {
        return new Venue(venueId, ORG, "Nhà hát Lớn", "Hà Nội", "1 Tràng Tiền", List.of(zones));
    }

    static VenueZone seatedZone(UUID venueId, String code) {
        return new VenueZone(UUID.randomUUID(), venueId, code, "Khu " + code, AdmissionKind.SEATED, 5, 10, null, 0);
    }

    static VenueZone standingZone(UUID venueId, String code, int capacity) {
        return new VenueZone(
                UUID.randomUUID(), venueId, code, "Khu " + code, AdmissionKind.STANDING, null, null, capacity, 1);
    }

    static Event event(UUID venueId, EventStatus status, EventSession... sessions) {
        return new Event(
                UUID.randomUUID(),
                ORG,
                venueId,
                new Slug("su-kien-thu"),
                "Sự kiện thử",
                "Tóm tắt",
                "Mô tả",
                "nhac-song",
                null,
                status,
                status == EventStatus.PUBLISHED ? NOW.minus(Duration.ofDays(1)) : null,
                List.of(sessions));
    }

    /** Suất hợp lệ: diễn sau 10 ngày, mở bán từ bây giờ tới giờ diễn. */
    static EventSession session(UUID sessionId, UUID eventId, TicketType... types) {
        Instant startsAt = NOW.plus(Duration.ofDays(10));
        return new EventSession(
                sessionId,
                eventId,
                startsAt,
                startsAt.plus(Duration.ofHours(2)),
                NOW,
                startsAt,
                null,
                null,
                null,
                null,
                List.of(types));
    }

    static TicketType ticket(UUID sessionId, VenueZone zone, long priceVnd) {
        return TicketType.create(sessionId, zone.id(), "Hạng " + zone.zoneCode(), priceVnd, 0);
    }

    static void assertApiError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(expected));
    }

    /** Lỗi phải là lỗi nghiệp vụ 4xx có mã — không phải ngoại lệ thô sẽ thành 500. */
    static void assertClientError(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(
                        e.errorCode().httpStatus())
                .isBetween(400, 499));
    }
}
