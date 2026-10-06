// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.application.command.SessionPublishedPayload;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hợp đồng của message {@code session.published}.
 *
 * <p>Đây là chỗ hai service gặp nhau, và là loại lỗi tệ nhất có thể xảy ra ở một hệ thống hàng đợi:
 * biên dịch xanh, test của từng service xanh, rồi mọi message đều rơi vào dead letter ở môi trường
 * thật. Test này khoá đúng những tên field mà {@code SessionPublishedListener} của inventory-service
 * đọc — đổi tên ở một bên là test đỏ ngay, không phải chờ tới lúc chạy.
 */
class SessionPublishedPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PurchaseLimits CAP = new PurchaseLimits(8, 10, 10, 20);

    @Test
    @DisplayName("khu ngồi trải thành từng ghế, đúng số lượng và đúng định dạng mã chỗ")
    void khu_ngoi_trai_thanh_tung_ghe() {
        Fixture fixture = seatedFixture(3, 4);

        SessionPublishedPayload payload =
                SessionPublishedPayload.of(fixture.event(), fixture.session(), fixture.venue(), CAP);

        assertThat(payload.seats()).hasSize(12);
        assertThat(payload.standingBlocks()).isEmpty();
        assertThat(payload.seats())
                .extracting(SessionPublishedPayload.Seat::seatCode)
                .contains("A-1-1", "A-3-4");
        assertThat(payload.seats())
                .allSatisfy(seat -> assertThat(seat.priceVnd()).isEqualTo(500_000));
    }

    @Test
    @DisplayName("mã chỗ không trùng nhau — đây là khoá duy nhất bên Inventory")
    void ma_cho_khong_trung_nhau() {
        // uq_seat_code của inventory là (event_session_id, seat_code). Trùng một mã là cả suất
        // diễn bị từ chối, và lỗi hiện ra ở service khác cách chỗ gây lỗi một chặng hàng đợi.
        Fixture fixture = seatedFixture(10, 20);

        assertThat(fixture.payload().seats())
                .extracting(SessionPublishedPayload.Seat::seatCode)
                .doesNotHaveDuplicates()
                .hasSize(200);
    }

    @Test
    @DisplayName("khu đứng gửi số lượng, không gửi từng đơn vị")
    void khu_dung_chi_gui_so_luong() {
        UUID venueId = UUID.randomUUID();
        VenueZone standing = new VenueZone(
                UUID.randomUUID(), venueId, "GA", "Sân trung tâm", AdmissionKind.STANDING, null, null, 3000, 0);
        Venue venue = new Venue(venueId, UUID.randomUUID(), "Sân vận động", "Hà Nội", null, List.of(standing));

        Event event = Event.draft(
                venue.organizationId(), venueId, new Slug("concert"), "Concert", null, null, "nhac-song", null);
        UUID sessionId = UUID.randomUUID();
        EventSession session = session(
                sessionId, event.id(), List.of(TicketType.create(sessionId, standing.id(), "Vé đứng", 1_500_000, 0)));

        SessionPublishedPayload payload = SessionPublishedPayload.of(event, session, venue, CAP);

        // 3.000 dòng chỗ cho một khu đứng là phình message mà không thêm thông tin: Inventory
        // tự sinh đơn vị ảo (ADR-1012).
        assertThat(payload.seats()).isEmpty();
        assertThat(payload.standingBlocks()).singleElement().satisfies(block -> {
            assertThat(block.zoneCode()).isEqualTo("GA");
            assertThat(block.quantity()).isEqualTo(3000);
        });
    }

    @Test
    @DisplayName("JSON mang đúng tên field mà inventory-service đọc")
    void json_dung_ten_field() throws Exception {
        JsonNode node = JSON.valueToTree(seatedFixture(2, 2).payload());

        // Danh sách này chép từ SessionPublishedListener.toManifest của inventory-service.
        assertThat(node.has("eventSessionId")).isTrue();
        assertThat(node.has("eventId")).isTrue();
        assertThat(node.has("organizationId")).isTrue();
        assertThat(node.has("salesOpenAt")).isTrue();
        assertThat(node.has("salesCloseAt")).isTrue();
        assertThat(node.path("limits").has("maxSeatedPerHold")).isTrue();
        assertThat(node.path("limits").has("maxTicketsPerCustomer")).isTrue();

        JsonNode seat = node.path("seats").get(0);
        assertThat(seat.has("seatCode")).isTrue();
        assertThat(seat.has("zoneCode")).isTrue();
        assertThat(seat.has("ticketTypeId")).isTrue();
        assertThat(seat.has("ticketTypeName")).isTrue();
        assertThat(seat.has("priceVnd")).isTrue();
        assertThat(seat.has("blocked")).isTrue();
    }

    @Test
    @DisplayName("mốc thời gian là chuỗi ISO-8601, không phải số epoch")
    void thoi_gian_la_chuoi_iso() throws Exception {
        // Bên nhận gọi Instant.parse(). Nếu ObjectMapper của một service nào đó bật
        // WRITE_DATES_AS_TIMESTAMPS thì payload thành số và bên nhận vỡ — chỉ ở runtime, chỉ với
        // message thật. Ép kiểu String ở record là cách chặn điều đó ngay từ hợp đồng.
        JsonNode node = JSON.valueToTree(seatedFixture(1, 1).payload());

        assertThat(node.path("salesOpenAt").isTextual()).isTrue();
        assertThat(Instant.parse(node.path("salesOpenAt").asText())).isNotNull();
    }

    @Test
    @DisplayName("hạng vé trỏ vào khu của địa điểm khác thì dừng lại, không publish nửa vời")
    void hang_ve_tro_sai_khu_thi_dung_lai() {
        Fixture fixture = seatedFixture(2, 2);
        UUID sessionId = fixture.session().id();
        EventSession lac = session(
                sessionId,
                fixture.event().id(),
                List.of(TicketType.create(sessionId, UUID.randomUUID(), "Hạng lạ", 100_000, 0)));

        assertThatThrownBy(() -> SessionPublishedPayload.of(fixture.event(), lac, fixture.venue(), CAP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không thuộc địa điểm");
    }

    @Test
    @DisplayName("toạ độ gửi sang Inventory là toạ độ của mặt bằng, không phải chỉ số hàng/cột")
    void toa_do_lay_tu_mat_bang() {
        // Trước khi có hình học, posX/posY là (số ghế, số hàng) — nên mọi sơ đồ đều là lưới đều và
        // mọi khu đều bắt đầu ở gốc. Đây là chốt chặn cho việc đó không quay lại: ghế giữa của một
        // hàng 3 ghế phải nằm đúng trên trục của khu, tức là posX = 0.
        SessionPublishedPayload payload = seatedFixture(2, 3).payload();

        SessionPublishedPayload.Seat giua = payload.seats().stream()
                .filter(seat -> seat.seatCode().equals("A-1-2"))
                .findFirst()
                .orElseThrow();

        assertThat(giua.posX()).isZero();

        // Hàng 2 phải lùi ra xa sân khấu hơn hàng 1. Dùng chỉ số hàng làm toạ độ cũng thoả điều
        // này, nên nó không đủ một mình — nhưng thiếu nó thì hai hàng chồng lên nhau.
        double hang1 = seatOf(payload, "A-1-2").posY();
        double hang2 = seatOf(payload, "A-2-2").posY();
        assertThat(hang2).isGreaterThan(hang1);
    }

    @Test
    @DisplayName("khu cung: mỗi ghế một chỗ, không ghế nào chồng lên ghế nào")
    void khu_cung_khong_co_ghe_trung_cho() {
        // Hai vé chỉ vào cùng một điểm trên sơ đồ là lỗi không ai báo: cả hai đều bán được, và chỉ
        // đến cửa soát vé mới lộ ra.
        Fixture fixture = arcFixture(3, 12);
        SessionPublishedPayload payload = fixture.payload();

        long choKhacNhau = payload.seats().stream()
                .map(seat -> seat.posX() + ":" + seat.posY())
                .distinct()
                .count();

        assertThat(payload.seats()).hasSize(36);
        assertThat(choKhacNhau).isEqualTo(36);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private record Fixture(Event event, EventSession session, Venue venue) {
        SessionPublishedPayload payload() {
            return SessionPublishedPayload.of(event, session, venue, CAP);
        }
    }

    private static Fixture seatedFixture(int rows, int seatsPerRow) {
        UUID venueId = UUID.randomUUID();
        VenueZone zone = new VenueZone(
                UUID.randomUUID(), venueId, "A", "Khu A", AdmissionKind.SEATED, rows, seatsPerRow, null, 0);
        Venue venue = new Venue(venueId, UUID.randomUUID(), "Nhà hát", "Hà Nội", null, List.of(zone));

        Event event = Event.draft(
                venue.organizationId(), venueId, new Slug("su-kien"), "Sự kiện", null, null, "nhac-song", null);
        UUID sessionId = UUID.randomUUID();
        EventSession session =
                session(sessionId, event.id(), List.of(TicketType.create(sessionId, zone.id(), "Hạng A", 500_000, 0)));

        return new Fixture(event, session, venue);
    }

    private static SessionPublishedPayload.Seat seatOf(SessionPublishedPayload payload, String seatCode) {
        return payload.seats().stream()
                .filter(seat -> seat.seatCode().equals(seatCode))
                .findFirst()
                .orElseThrow();
    }

    /** Một khu hình cung — khán phòng vây quanh sân khấu, hình dạng của gần như mọi concert. */
    private static Fixture arcFixture(int rows, int seatsPerRow) {
        UUID venueId = UUID.randomUUID();
        VenueZone zone = new VenueZone(
                        UUID.randomUUID(), venueId, "A", "Khu A", AdmissionKind.SEATED, rows, seatsPerRow, null, 0)
                .withLayout(ZoneLayout.arc(0, 0, 12, 20, 160));
        Venue venue = new Venue(venueId, UUID.randomUUID(), "Nhà thi đấu", "Hà Nội", null, List.of(zone));

        Event event = Event.draft(
                venue.organizationId(), venueId, new Slug("su-kien-cung"), "Sự kiện", null, null, "nhac-song", null);
        UUID sessionId = UUID.randomUUID();
        EventSession session =
                session(sessionId, event.id(), List.of(TicketType.create(sessionId, zone.id(), "Hạng A", 500_000, 0)));

        return new Fixture(event, session, venue);
    }

    private static EventSession session(UUID sessionId, UUID eventId, List<TicketType> tickets) {
        return new EventSession(
                sessionId,
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
}
