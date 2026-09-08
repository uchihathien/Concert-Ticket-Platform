// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.PublishBlocker;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Luật "sẵn sàng để bán".
 *
 * <p>Test ở tầng domain, không qua HTTP: đây là luật nghiệp vụ thuần, và nó được đọc từ hai phía —
 * nút Publish gọi nó để chặn, còn màn hình quản trị gọi nó để vẽ checklist. Hai phía đó phải luôn
 * nói cùng một điều, nên nó chỉ được có một định nghĩa và định nghĩa đó phải có test riêng.
 */
class EventPreflightTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("đủ khu, đủ suất, đủ hạng vé, cửa bán hợp lệ: không còn vướng mắc")
    void san_sang_thi_khong_con_vuong_mac() {
        Venue venue = venueWithZone();
        Event event = draft(venue);
        event.addSession(sessionWithTicket(event.id(), venue));

        assertThat(event.preflight(venue)).isEmpty();
    }

    @Test
    @DisplayName("chưa có suất diễn nào")
    void thieu_suat_dien() {
        Venue venue = venueWithZone();

        assertThat(draft(venue).preflight(venue)).containsExactly(PublishBlocker.NO_SESSION);
    }

    @Test
    @DisplayName("có suất nhưng chưa khai hạng vé nào")
    void suat_khong_ban_duoc_gi() {
        Venue venue = venueWithZone();
        Event event = draft(venue);
        event.addSession(session(event.id(), List.of()));

        assertThat(event.preflight(venue)).containsExactly(PublishBlocker.SESSION_WITHOUT_TICKET_TYPE);
    }

    @Test
    @DisplayName("địa điểm chưa khai khu nào")
    void dia_diem_khong_co_khu() {
        Venue empty = new Venue(UUID.randomUUID(), UUID.randomUUID(), "Chưa khai khu", "Hà Nội", null, List.of());
        Event event = draft(empty);
        event.addSession(session(event.id(), List.of()));

        assertThat(event.preflight(empty)).contains(PublishBlocker.VENUE_WITHOUT_ZONE);
    }

    @Test
    @DisplayName("cửa bán mở sau khi suất đã diễn")
    void cua_ban_mo_qua_muon() {
        Venue venue = venueWithZone();
        Event event = draft(venue);
        Instant startsAt = NOW.plus(Duration.ofDays(10));
        EventSession broken = new EventSession(
                UUID.randomUUID(),
                event.id(),
                startsAt,
                startsAt.plus(Duration.ofHours(2)),
                startsAt.plus(Duration.ofDays(1)), // mở bán SAU khi diễn xong
                startsAt.plus(Duration.ofDays(2)),
                null,
                null,
                null,
                null,
                List.of(ticket(UUID.randomUUID(), venue)));
        event.addSession(broken);

        assertThat(event.preflight(venue)).containsExactly(PublishBlocker.INVALID_SALES_WINDOW);
    }

    @Test
    @DisplayName("trả về HẾT vướng mắc trong một lần, không dừng ở cái đầu tiên")
    void tra_ve_het_vuong_mac() {
        // Màn hình publish là một checklist. Dừng ở lỗi đầu tiên thì ban tổ chức phải sửa một
        // chỗ rồi bấm lại để lộ ra chỗ tiếp theo — đúng thứ mà checklist sinh ra để tránh.
        Venue empty = new Venue(UUID.randomUUID(), UUID.randomUUID(), "Trống", "Hà Nội", null, List.of());

        assertThat(draft(empty).preflight(empty))
                .containsExactlyInAnyOrder(PublishBlocker.VENUE_WITHOUT_ZONE, PublishBlocker.NO_SESSION);
    }

    @Test
    @DisplayName("cửa bán kéo dài quá giờ diễn thì vẫn hợp lệ")
    void ban_ve_cho_nguoi_den_muon_van_hop_le() {
        // Không phải sơ suất: nhiều sự kiện vẫn bán vé sau khi đã bắt đầu. Cấm điều đó là áp một
        // giả định về loại sự kiện lên toàn bộ nền tảng.
        Venue venue = venueWithZone();
        Event event = draft(venue);
        Instant startsAt = NOW.plus(Duration.ofDays(10));
        event.addSession(new EventSession(
                UUID.randomUUID(),
                event.id(),
                startsAt,
                startsAt.plus(Duration.ofHours(3)),
                NOW,
                startsAt.plus(Duration.ofHours(1)),
                null,
                null,
                null,
                null,
                List.of(ticket(UUID.randomUUID(), venue))));

        assertThat(event.preflight(venue)).isEmpty();
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private static Venue venueWithZone() {
        UUID venueId = UUID.randomUUID();
        VenueZone zone = new VenueZone(UUID.randomUUID(), venueId, "A", "Khu A", AdmissionKind.SEATED, 10, 20, null, 0);
        return new Venue(venueId, UUID.randomUUID(), "Nhà hát", "Hà Nội", "1 Tràng Tiền", List.of(zone));
    }

    private static Event draft(Venue venue) {
        return Event.draft(
                venue.organizationId(),
                venue.id(),
                new Slug("su-kien-thu"),
                "Sự kiện thử",
                null,
                null,
                "nhac-song",
                null);
    }

    private static EventSession sessionWithTicket(UUID eventId, Venue venue) {
        UUID sessionId = UUID.randomUUID();
        return new EventSession(
                sessionId,
                eventId,
                NOW.plus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(10)).plus(Duration.ofHours(2)),
                NOW,
                NOW.plus(Duration.ofDays(10)),
                null,
                null,
                null,
                null,
                List.of(ticket(sessionId, venue)));
    }

    private static EventSession session(UUID eventId, List<TicketType> tickets) {
        return new EventSession(
                UUID.randomUUID(),
                eventId,
                NOW.plus(Duration.ofDays(10)),
                null,
                NOW,
                NOW.plus(Duration.ofDays(10)),
                null,
                null,
                null,
                null,
                tickets);
    }

    private static TicketType ticket(UUID sessionId, Venue venue) {
        return TicketType.create(sessionId, venue.zones().get(0).id(), "Hạng A", 500_000, 0);
    }
}
