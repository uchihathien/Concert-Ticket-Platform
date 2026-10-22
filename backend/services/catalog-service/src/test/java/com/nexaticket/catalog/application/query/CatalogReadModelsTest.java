// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.CatalogProperties;
import com.nexaticket.catalog.application.PlatformAccess;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.SalesReportPort;
import com.nexaticket.catalog.domain.port.SeatStatusPort;
import com.nexaticket.catalog.domain.port.UpstreamUnavailableException;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Các đường đọc: giá cho ordering, bảng xếp hạng, bảng điều khiển của tổ chức, khung concert.
 *
 * <p>Điểm chung đáng kiểm: <b>service phía sau chết thì đường đọc không chết theo</b> — trả phần
 * mình có và nói rõ phần nào thiếu — còn lỗi của người gọi thì phải là 4xx có mã.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogReadModelsTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

    @Mock
    CatalogQueries queries;

    @Mock
    EventRepository events;

    @Mock
    VenueRepository venues;

    @Mock
    SalesReportPort sales;

    @Mock
    SeatStatusPort seatStatus;

    @Mock
    CatalogAccess access;

    @Mock
    PlatformAccess platform;

    @Mock
    ConcertTemplateRepository templates;

    @Nested
    @DisplayName("Giá cho ordering")
    class GiaChoOrdering {

        private PricingQuery pricing() {
            return new PricingQuery(queries, new CatalogProperties(0, 0, 0, 0, 750, false, null));
        }

        @Test
        @DisplayName("hoa hồng lấy từ cấu hình phía server, giảm giá 0 khi không có mã")
        void hoa_hong_tu_server() {
            UUID session = UUID.randomUUID();
            when(queries.sessionExists(session)).thenReturn(true);

            PricingQuery.Pricing result = pricing().resolve(session, null, 1_000_000);

            assertThat(result.commissionBps()).isEqualTo(750);
            assertThat(result.discountVnd()).isZero();
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("mã khuyến mãi rỗng được coi như không nhập")
        void ma_rong(String code) {
            UUID session = UUID.randomUUID();
            when(queries.sessionExists(session)).thenReturn(true);

            assertThat(pricing().resolve(session, code, 0).discountVnd()).isZero();
        }

        @Test
        @DisplayName("có mã khuyến mãi (chưa hỗ trợ): 422 PROMOTION_INVALID, không âm thầm bỏ qua")
        void co_ma() {
            UUID session = UUID.randomUUID();
            when(queries.sessionExists(session)).thenReturn(true);

            assertThatThrownBy(() -> pricing().resolve(session, "GIAM50", 1_000))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.PROMOTION_INVALID));
        }

        @Test
        @DisplayName("suất không tồn tại: 404 SESSION_NOT_FOUND")
        void suat_khong_ton_tai() {
            assertThatThrownBy(() -> pricing().resolve(UUID.randomUUID(), null, 0))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.SESSION_NOT_FOUND));
        }

        @Test
        @DisplayName("tạm tính âm: lỗi 4xx có mã, không phải IllegalArgumentException → 500")
        void tam_tinh_am() {
            // PricingQuery ném IllegalArgumentException; GlobalExceptionHandler không có nhánh cho
            // nó nên ordering nhận 500 "Unexpected error" — và hiểu nhầm thành Catalog đang sập,
            // trong khi đó là lỗi của chính request.
            assertThatThrownBy(() -> pricing().resolve(UUID.randomUUID(), null, -1))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.errorCode().httpStatus()).isBetween(400, 499));
        }
    }

    @Nested
    @DisplayName("Bán chạy")
    class BanChay {

        @Test
        @DisplayName("giữ đúng thứ tự xếp hạng của analytics")
        void giu_thu_tu() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(sales.trendingEventIds(8)).thenReturn(List.of(b, a));
            List<CatalogViews.EventCard> cards = List.of(card("b"), card("a"));
            when(queries.publishedEventsByIds(List.of(b, a))).thenReturn(cards);

            assertThat(new TrendingEventsQuery(sales, queries).handle(8)).isEqualTo(cards);
        }

        @Test
        @DisplayName("analytics chết: trả rỗng, không lấp bằng sự kiện bán chậm, không 5xx")
        void analytics_chet() {
            when(sales.trendingEventIds(8)).thenThrow(new UpstreamUnavailableException("analytics", "timeout", null));

            assertThat(new TrendingEventsQuery(sales, queries).handle(8)).isEmpty();
            verify(queries, never()).publishedEventsByIds(any());
        }
    }

    @Nested
    @DisplayName("Suất cho soát vé")
    class SuatSoatVe {

        @Test
        @DisplayName("suất không tồn tại: 404")
        void khong_ton_tai() {
            UUID id = UUID.randomUUID();
            when(queries.sessionInfo(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> new CheckinSessionQuery(queries).info(id))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.SESSION_NOT_FOUND));
        }

        @Test
        @DisplayName("danh bạ suất bọc nguyên danh sách của cổng đọc")
        void danh_ba() {
            SessionDirectory.Entry entry =
                    new SessionDirectory.Entry(UUID.randomUUID(), UUID.randomUUID(), "A", "PUBLISHED", NOW, null);

            var listing = new SessionDirectoryQuery(org -> List.of(entry)).forOrganization(ORG);

            assertThat(listing.sessions()).containsExactly(entry);
        }
    }

    @Nested
    @DisplayName("Bảng điều khiển của tổ chức")
    class BangDieuKhien {

        private OrganizationDashboardQuery query() {
            return new OrganizationDashboardQuery(events, venues, queries, seatStatus, sales, access);
        }

        @Test
        @DisplayName("tổng quan: đếm theo trạng thái, cộng sức chứa, gộp doanh số theo sự kiện")
        void tong_quan() {
            UUID e1 = UUID.randomUUID();
            UUID e2 = UUID.randomUUID();
            when(queries.organizationEvents(ORG))
                    .thenReturn(List.of(
                            row(e1, "PUBLISHED", 100), row(e2, "DRAFT", 50), row(UUID.randomUUID(), "CANCELLED", 0)));
            when(sales.forOrganization(ORG))
                    .thenReturn(List.of(
                            new SalesReportPort.SessionSales(UUID.randomUUID(), e1, 3, 300_000, 2, 0, 0),
                            new SalesReportPort.SessionSales(UUID.randomUUID(), e1, 2, 200_000, 1, 1, 0)));

            DashboardViews.OrganizationDashboard dashboard = query().overview(ORG);

            assertThat(dashboard.totals()).isEqualTo(new DashboardViews.DashboardTotals(3, 1, 1, 150, 5, 500_000));
            assertThat(dashboard.eventSales()).containsExactly(new DashboardViews.EventSales(e1, 5, 500_000));
            assertThat(dashboard.degraded()).isEmpty();
        }

        @Test
        @DisplayName("analytics chết: doanh số rỗng và degraded nêu tên service — không báo 0đ như thật")
        void analytics_chet() {
            when(queries.organizationEvents(ORG)).thenReturn(List.of());
            when(sales.forOrganization(ORG)).thenThrow(new UpstreamUnavailableException("analytics", "down", null));

            DashboardViews.OrganizationDashboard dashboard = query().overview(ORG);

            assertThat(dashboard.eventSales()).isEmpty();
            assertThat(dashboard.degraded()).containsExactly("analytics");
        }

        @Test
        @DisplayName("không đủ quyền: không hỏi analytics, không đọc sự kiện")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertThatThrownBy(() -> query().overview(ORG))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(queries, sales);
        }

        @Test
        @DisplayName("master data: sự kiện của tổ chức khác → 404 trước khi hỏi service khác")
        void master_data_khong_thuoc() {
            UUID eventId = UUID.randomUUID();
            when(events.findByIdForOrganization(ORG, eventId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> query().masterData(ORG, eventId))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.EVENT_NOT_FOUND));
            verifyNoInteractions(sales, seatStatus);
        }

        @Test
        @DisplayName("master data: inventory chết thì seating null, degraded có inventory, phần catalog vẫn đủ")
        void master_data_inventory_chet() {
            UUID venueId = UUID.randomUUID();
            VenueZone zone = new VenueZone(UUID.randomUUID(), venueId, "A", "A", AdmissionKind.SEATED, 10, 10, null, 0);
            Venue venue = new Venue(venueId, ORG, "Nhà hát", "Hà Nội", null, List.of(zone));
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            Event event = event(venueId, session(s1, zone), session(s2, zone));
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));
            when(venues.findById(ORG, venueId)).thenReturn(Optional.of(venue));
            when(sales.forEvent(event.id()))
                    .thenReturn(List.of(new SalesReportPort.SessionSales(s1, event.id(), 7, 700_000, 3, 0, 1)));
            when(seatStatus.forSession(any())).thenThrow(new UpstreamUnavailableException("inventory", "down", null));

            DashboardViews.EventMasterData data = query().masterData(ORG, event.id());

            assertThat(data.degraded()).containsExactly("inventory");
            assertThat(data.sessions()).hasSize(2).allSatisfy(r -> assertThat(r.seating())
                    .isNull());
            assertThat(data.totals().declaredCapacity()).isEqualTo(200);
            assertThat(data.totals().materializedSeats()).isZero();
            assertThat(data.totals().ticketsSold()).isEqualTo(7);
            assertThat(data.totals().grossVnd()).isEqualTo(700_000);
            assertThat(data.event().title()).isEqualTo("Sự kiện");
        }

        @Test
        @DisplayName("master data: suất chưa lên bán (inventory trả rỗng) không phải lỗi, không degraded")
        void master_data_chua_len_ban() {
            UUID venueId = UUID.randomUUID();
            VenueZone zone = new VenueZone(UUID.randomUUID(), venueId, "A", "A", AdmissionKind.SEATED, 1, 1, null, 0);
            Venue venue = new Venue(venueId, ORG, "Nhà hát", "Hà Nội", null, List.of(zone));
            Event event = event(venueId, session(UUID.randomUUID(), zone));
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));
            when(venues.findById(ORG, venueId)).thenReturn(Optional.of(venue));
            when(sales.forEvent(event.id())).thenReturn(List.of());
            when(seatStatus.forSession(any())).thenReturn(Optional.empty());

            DashboardViews.EventMasterData data = query().masterData(ORG, event.id());

            assertThat(data.degraded()).isEmpty();
            assertThat(data.sessions().get(0).seating()).isNull();
            assertThat(data.sessions().get(0).sales()).isNull();
        }

        @Test
        @DisplayName("master data: cộng trạng thái ghế theo khu thành tổng của suất")
        void master_data_cong_ghe() {
            UUID venueId = UUID.randomUUID();
            VenueZone zone = new VenueZone(UUID.randomUUID(), venueId, "A", "A", AdmissionKind.SEATED, 2, 5, null, 0);
            Venue venue = new Venue(venueId, ORG, "Nhà hát", "Hà Nội", null, List.of(zone));
            UUID s = UUID.randomUUID();
            Event event = event(venueId, session(s, zone));
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));
            when(venues.findById(ORG, venueId)).thenReturn(Optional.of(venue));
            when(sales.forEvent(event.id())).thenReturn(List.of());
            when(seatStatus.forSession(s))
                    .thenReturn(Optional.of(new SeatStatusPort.SessionSeatStatus(
                            s,
                            42,
                            List.of(
                                    new SeatStatusPort.ZoneSeatStatus("A", "SEATED", 4, 1, 1, 3, 1),
                                    new SeatStatusPort.ZoneSeatStatus("SAN", "STANDING", 10, 0, 0, 5, 0)))));

            DashboardViews.EventMasterData data = query().masterData(ORG, event.id());

            DashboardViews.SessionSeating seating = data.sessions().get(0).seating();
            assertThat(seating.availabilityVersion()).isEqualTo(42);
            assertThat(seating.totals()).isEqualTo(new DashboardViews.SeatCounts(14, 1, 1, 8, 1, 25));
            assertThat(data.totals().seatsSold()).isEqualTo(8);
            assertThat(data.totals().materializedSeats()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("Khung concert — đường đọc")
    class DocKhung {

        private TemplateQueries query() {
            return new TemplateQueries(templates, platform, access);
        }

        @Test
        @DisplayName("tổ chức hỏi khung DRAFT hoặc ARCHIVED: 404 như thể không tồn tại")
        void khung_khong_active() {
            ConcertTemplate draft =
                    new ConcertTemplate(UUID.randomUUID(), "A", "A", "c", null, TemplateStatus.DRAFT, List.of());
            ConcertTemplate archived =
                    new ConcertTemplate(UUID.randomUUID(), "B", "B", "c", null, TemplateStatus.ARCHIVED, List.of());
            when(templates.findById(draft.id())).thenReturn(Optional.of(draft));
            when(templates.findById(archived.id())).thenReturn(Optional.of(archived));

            assertThatThrownBy(() -> query().usableById(ORG, draft.id()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.TEMPLATE_NOT_FOUND));
            assertThatThrownBy(() -> query().usableById(ORG, archived.id())).isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("danh sách cho tổ chức chỉ hỏi khung ACTIVE")
        void danh_sach_active() {
            ConcertTemplate active = new ConcertTemplate(
                    UUID.randomUUID(),
                    "A",
                    "A",
                    "c",
                    null,
                    TemplateStatus.ACTIVE,
                    List.of(TemplateZone.create(UUID.randomUUID(), "V", "V", AdmissionKind.SEATED, 3, 3, null, 0, 1L)));
            when(templates.findAll(TemplateStatus.ACTIVE)).thenReturn(List.of(active));

            var rows = query().usableBy(ORG);

            assertThat(rows).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo("ACTIVE");
                assertThat(r.capacity()).isEqualTo(9);
            });
            verify(templates, never()).findAll(null);
        }

        @Test
        @DisplayName("lọc theo trạng thái không phân biệt hoa thường; bỏ trống là lấy hết")
        void loc_trang_thai() {
            query().all("archived");
            query().all(" ");

            verify(templates).findAll(TemplateStatus.ARCHIVED);
            verify(templates).findAll(null);
        }

        @Test
        @DisplayName("trạng thái lạ bị từ chối có mã thay vì trả về mọi khung")
        void trang_thai_la() {
            assertThatThrownBy(() -> query().all("ACTIVEX")).isInstanceOf(ApiException.class);
            verify(templates, never()).findAll(any());
        }

        @Test
        @DisplayName("khu vực nền tảng: không phải superadmin thì 403, không đọc kho")
        void khong_phai_superadmin() {
            doThrow(ApiException.forbidden("Requires SUPER_ADMIN"))
                    .when(platform)
                    .requireSuperAdmin();

            assertThatThrownBy(() -> query().all(null)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> query().byId(UUID.randomUUID())).isInstanceOf(ApiException.class);
            verifyNoInteractions(templates);
        }
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private static CatalogViews.EventCard card(String slug) {
        return new CatalogViews.EventCard(slug, slug, null, "c", null, "Hà Nội", "V", NOW, 1L, 1);
    }

    private static CatalogViews.AdminEventRow row(UUID id, String status, int capacity) {
        return new CatalogViews.AdminEventRow(id, "s", "t", "c", status, null, "v", null, 1, 1, capacity);
    }

    private static Event event(UUID venueId, EventSession... sessions) {
        return new Event(
                UUID.randomUUID(),
                ORG,
                venueId,
                new Slug("su-kien"),
                "Sự kiện",
                null,
                null,
                "c",
                null,
                EventStatus.PUBLISHED,
                NOW,
                List.of(sessions));
    }

    private static EventSession session(UUID id, VenueZone zone) {
        Instant startsAt = NOW.plus(Duration.ofDays(3));
        return new EventSession(
                id,
                null,
                startsAt,
                null,
                NOW,
                startsAt,
                null,
                null,
                null,
                null,
                List.of(TicketType.create(id, zone.id(), "Hạng", 100_000, 0)));
    }
}
