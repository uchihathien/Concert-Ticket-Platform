// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import static com.nexaticket.catalog.application.command.CatalogFixtures.NOW;
import static com.nexaticket.catalog.application.command.CatalogFixtures.ORG;
import static com.nexaticket.catalog.application.command.CatalogFixtures.assertApiError;
import static com.nexaticket.catalog.application.command.CatalogFixtures.assertClientError;
import static com.nexaticket.catalog.application.command.CatalogFixtures.event;
import static com.nexaticket.catalog.application.command.CatalogFixtures.seatedZone;
import static com.nexaticket.catalog.application.command.CatalogFixtures.session;
import static com.nexaticket.catalog.application.command.CatalogFixtures.ticket;
import static com.nexaticket.catalog.application.command.CatalogFixtures.venueWithZones;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.EditableEvent;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * Nội dung bán hàng của sự kiện: suất diễn và hạng vé.
 *
 * <p>Luật chung của cả bốn lệnh: <b>đang bán thì không sửa</b> (tồn kho đã dựng ở Inventory), và
 * mọi id trên đường dẫn phải thật sự nằm trong sự kiện của tổ chức đó.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionAndTicketTypeHandlersTest {

    @Mock
    EventRepository events;

    @Mock
    VenueRepository venues;

    @Mock
    CatalogAccess access;

    private final UUID venueId = UUID.randomUUID();
    private VenueZone zoneA;
    private VenueZone zoneB;

    @BeforeEach
    void dungDiaDiem() {
        zoneA = seatedZone(venueId, "A");
        zoneB = seatedZone(venueId, "B");
        Venue venue = venueWithZones(venueId, zoneA, zoneB);
        when(venues.findById(ORG, venueId)).thenReturn(Optional.of(venue));
    }

    private Event stored(Event event) {
        when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));
        return event;
    }

    @Nested
    @DisplayName("Thêm suất diễn")
    class ThemSuat {

        private AddSessionHandler handler() {
            return new AddSessionHandler(events, access);
        }

        private final Instant startsAt = NOW.plus(Duration.ofDays(30));

        @Test
        @DisplayName("bản nháp: thêm được, mốc thời gian và trần mua vé đi nguyên vào repository")
        void them_suat() {
            Event draft = stored(event(venueId, EventStatus.DRAFT));

            UUID id = handler()
                    .handle(ORG, draft.id(), startsAt, startsAt.plusSeconds(7200), NOW, startsAt, 4, null, null, 10);

            ArgumentCaptor<EventSession> saved = ArgumentCaptor.forClass(EventSession.class);
            verify(events).addSession(saved.capture());
            assertThat(saved.getValue().id()).isEqualTo(id);
            assertThat(saved.getValue().eventId()).isEqualTo(draft.id());
            assertThat(saved.getValue().startsAt()).isEqualTo(startsAt);
            assertThat(saved.getValue().maxSeatedPerHold()).isEqualTo(4);
            assertThat(saved.getValue().maxStandingPerHold()).isNull();
            assertThat(saved.getValue().maxTicketsPerCustomer()).isEqualTo(10);
        }

        @Test
        @DisplayName("đang bán: 409 — suất mới sẽ không có tồn kho")
        void dang_ban() {
            Event published = stored(event(venueId, EventStatus.PUBLISHED));

            assertApiError(
                    () -> handler().handle(ORG, published.id(), startsAt, null, NOW, startsAt, null, null, null, null),
                    CatalogErrorCode.INVALID_EVENT_STATE);
            verify(events, never()).addSession(any());
        }

        @Test
        @DisplayName("sự kiện của tổ chức khác: 404")
        void cua_to_chuc_khac() {
            UUID eventId = UUID.randomUUID();
            when(events.findByIdForOrganization(ORG, eventId)).thenReturn(Optional.empty());

            assertApiError(
                    () -> handler().handle(ORG, eventId, startsAt, null, NOW, startsAt, null, null, null, null),
                    CatalogErrorCode.EVENT_NOT_FOUND);
        }

        @Test
        @DisplayName("không đủ quyền: 403 và không đọc sự kiện")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertApiError(
                    () -> handler()
                            .handle(ORG, UUID.randomUUID(), startsAt, null, NOW, startsAt, null, null, null, null),
                    ErrorCode.Common.FORBIDDEN);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("cửa bán đóng trước khi mở: lỗi nghiệp vụ 4xx, không đẩy xuống ràng buộc database")
        void cua_ban_nguoc() {
            // event_sessions có CHECK (sales_close_at > sales_open_at). Handler không kiểm trước,
            // nên request này tới được INSERT, nổ DataIntegrityViolationException, và người dùng
            // nhận 500 "Unexpected error" — kèm luôn việc mã INVALID_SALES_WINDOW của preflight
            // không bao giờ xuất hiện được cho trường hợp đóng-trước-mở.
            Event draft = stored(event(venueId, EventStatus.DRAFT));

            assertClientError(() -> handler()
                    .handle(ORG, draft.id(), startsAt, null, NOW.plusSeconds(3600), NOW, null, null, null, null));
            verify(events, never()).addSession(any());
        }
    }

    @Nested
    @DisplayName("Sửa và xoá suất diễn")
    class SuaSuat {

        private EditSessionHandler handler() {
            return new EditSessionHandler(events, new EditableEvent(events, access));
        }

        @Test
        @DisplayName("mốc null giữ nguyên; trần mua vé null nghĩa là về mặc định nền tảng")
        void moc_null_giu_nguyen() {
            UUID s = UUID.randomUUID();
            EventSession existing = new EventSession(
                    s,
                    null,
                    NOW.plus(Duration.ofDays(5)),
                    null,
                    NOW,
                    NOW.plus(Duration.ofDays(5)),
                    4,
                    4,
                    4,
                    4,
                    java.util.List.of(ticket(s, zoneA, 100)));
            Event draft = stored(event(venueId, EventStatus.DRAFT, existing));
            Instant newStart = NOW.plus(Duration.ofDays(6));

            handler().update(ORG, draft.id(), s, newStart, null, null, null, null, 2, null, null);

            ArgumentCaptor<EventSession> saved = ArgumentCaptor.forClass(EventSession.class);
            verify(events).updateSession(saved.capture());
            assertThat(saved.getValue().startsAt()).isEqualTo(newStart);
            assertThat(saved.getValue().salesOpenAt()).isEqualTo(existing.salesOpenAt());
            assertThat(saved.getValue().salesCloseAt()).isEqualTo(existing.salesCloseAt());
            assertThat(saved.getValue().maxSeatedPerHold()).isNull();
            assertThat(saved.getValue().maxStandingPerHold()).isEqualTo(2);
            assertThat(saved.getValue().ticketTypes()).hasSize(1);
        }

        @Test
        @DisplayName("đang bán: không sửa, không xoá")
        void dang_ban() {
            UUID s = UUID.randomUUID();
            Event published = stored(event(venueId, EventStatus.PUBLISHED, session(s, null)));

            assertApiError(
                    () -> handler().update(ORG, published.id(), s, NOW, null, null, null, null, null, null, null),
                    CatalogErrorCode.INVALID_EVENT_STATE);
            assertApiError(() -> handler().delete(ORG, published.id(), s), CatalogErrorCode.INVALID_EVENT_STATE);
            verify(events, never()).updateSession(any());
            verify(events, never()).deleteSession(any());
        }

        @Test
        @DisplayName("suất không thuộc sự kiện này: 404, kể cả khi id có thật ở sự kiện khác")
        void suat_cua_su_kien_khac() {
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(UUID.randomUUID(), null)));
            UUID foreign = UUID.randomUUID();

            assertApiError(() -> handler().delete(ORG, draft.id(), foreign), CatalogErrorCode.SESSION_NOT_FOUND);
            verify(events, never()).deleteSession(any());
        }

        @Test
        @DisplayName("xoá suất của bản nháp: gọi đúng id")
        void xoa_suat() {
            UUID s = UUID.randomUUID();
            Event draft = stored(event(venueId, EventStatus.UNPUBLISHED, session(s, null)));

            handler().delete(ORG, draft.id(), s);

            verify(events).deleteSession(s);
        }

        @Test
        @DisplayName("sửa cửa bán thành đóng-trước-mở: lỗi nghiệp vụ 4xx, không phải 500 từ ràng buộc database")
        void sua_cua_ban_nguoc() {
            // Cùng gốc với AddSessionHandler: EditSessionHandler cố ý không kiểm cửa bán ("để lưu
            // bản nháp dở dang"), nhưng ck_sales_window ở database không cho lưu — kết quả là 500.
            UUID s = UUID.randomUUID();
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null)));

            assertClientError(() -> handler()
                    .update(ORG, draft.id(), s, null, null, NOW.plus(Duration.ofDays(2)), NOW, null, null, null, null));
            verify(events, never()).updateSession(any());
        }
    }

    @Nested
    @DisplayName("Thêm hạng vé")
    class ThemHangVe {

        private AddTicketTypeHandler handler() {
            return new AddTicketTypeHandler(events, venues, access);
        }

        @Test
        @DisplayName("khai giá cho một khu của đúng địa điểm")
        void khai_gia() {
            UUID s = UUID.randomUUID();
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null)));

            handler().handle(ORG, draft.id(), s, zoneA.id(), "Hạng A", 750_000, 1);

            ArgumentCaptor<TicketType> saved = ArgumentCaptor.forClass(TicketType.class);
            verify(events).addTicketType(saved.capture());
            assertThat(saved.getValue().venueZoneId()).isEqualTo(zoneA.id());
            assertThat(saved.getValue().eventSessionId()).isEqualTo(s);
            assertThat(saved.getValue().priceVnd()).isEqualTo(750_000);
        }

        @Test
        @DisplayName("khu của địa điểm khác: 404 ZONE_NOT_FOUND — không dựng tồn kho với mã khu của người khác")
        void khu_cua_dia_diem_khac() {
            UUID s = UUID.randomUUID();
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null)));

            assertApiError(
                    () -> handler().handle(ORG, draft.id(), s, UUID.randomUUID(), "X", 1, 0),
                    CatalogErrorCode.ZONE_NOT_FOUND);
            verify(events, never()).addTicketType(any());
        }

        @Test
        @DisplayName("suất không thuộc sự kiện: 404 SESSION_NOT_FOUND")
        void suat_khong_thuoc() {
            Event draft = stored(event(venueId, EventStatus.DRAFT));

            assertApiError(
                    () -> handler().handle(ORG, draft.id(), UUID.randomUUID(), zoneA.id(), "X", 1, 0),
                    CatalogErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("khai giá lần hai cho cùng khu: 409 ZONE_ALREADY_PRICED")
        void khai_hai_lan() {
            UUID s = UUID.randomUUID();
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null)));
            doThrow(new DuplicateKeyException("uq_type_zone")).when(events).addTicketType(any());

            assertApiError(
                    () -> handler().handle(ORG, draft.id(), s, zoneA.id(), "X", 1, 0),
                    CatalogErrorCode.ZONE_ALREADY_PRICED);
        }

        @Test
        @DisplayName("đang bán: 409 trước khi chạm địa điểm")
        void dang_ban() {
            UUID s = UUID.randomUUID();
            Event published = stored(event(venueId, EventStatus.PUBLISHED, session(s, null)));

            assertApiError(
                    () -> handler().handle(ORG, published.id(), s, zoneA.id(), "X", 1, 0),
                    CatalogErrorCode.INVALID_EVENT_STATE);
            verify(events, never()).addTicketType(any());
        }

        @Test
        @DisplayName("giá vé 0 đồng được nhận — vé mời")
        void gia_khong_dong() {
            UUID s = UUID.randomUUID();
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null)));

            handler().handle(ORG, draft.id(), s, zoneB.id(), "Mời", 0, 0);

            verify(events).addTicketType(any());
        }
    }

    @Nested
    @DisplayName("Sửa và xoá hạng vé")
    class SuaHangVe {

        private EditTicketTypeHandler handler() {
            return new EditTicketTypeHandler(events, new EditableEvent(events, access));
        }

        @Test
        @DisplayName("giá âm (giá trị canh của controller) và tên trống nghĩa là giữ nguyên")
        void giu_nguyen() {
            UUID s = UUID.randomUUID();
            TicketType type = ticket(s, zoneA, 500_000);
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null, type)));

            handler().update(ORG, draft.id(), s, type.id(), " ", -1, null);

            verify(events).updateTicketType(type.id(), type.name(), 500_000, type.sortOrder());
        }

        @Test
        @DisplayName("sửa giá thành 5.000.000")
        void sua_gia() {
            UUID s = UUID.randomUUID();
            TicketType type = ticket(s, zoneA, 500_000);
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null, type)));

            handler().update(ORG, draft.id(), s, type.id(), "VIP", 5_000_000, 3);

            verify(events).updateTicketType(type.id(), "VIP", 5_000_000, 3);
        }

        @Test
        @DisplayName("hạng vé nằm ở suất khác của cùng sự kiện: 404, không sửa xuyên suất")
        void hang_ve_cua_suat_khac() {
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            TicketType inS2 = ticket(s2, zoneA, 1);
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s1, null), session(s2, null, inS2)));

            assertApiError(
                    () -> handler().update(ORG, draft.id(), s1, inS2.id(), "x", 1, 0),
                    CatalogErrorCode.TICKET_TYPE_NOT_FOUND);
            assertApiError(
                    () -> handler().delete(ORG, draft.id(), s1, inS2.id()), CatalogErrorCode.TICKET_TYPE_NOT_FOUND);
            verify(events, never()).updateTicketType(any(), anyString(), anyLong(), anyInt());
            verify(events, never()).deleteTicketType(any());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"PUBLISHED"})
        @DisplayName("đang bán: không sửa giá, không xoá hạng vé")
        void dang_ban(EventStatus status) {
            UUID s = UUID.randomUUID();
            TicketType type = ticket(s, zoneA, 1);
            Event published = stored(event(venueId, status, session(s, null, type)));

            assertApiError(
                    () -> handler().update(ORG, published.id(), s, type.id(), "x", 2, 0),
                    CatalogErrorCode.INVALID_EVENT_STATE);
            assertApiError(
                    () -> handler().delete(ORG, published.id(), s, type.id()), CatalogErrorCode.INVALID_EVENT_STATE);
        }

        @Test
        @DisplayName("xoá hạng vé của bản nháp")
        void xoa() {
            UUID s = UUID.randomUUID();
            TicketType type = ticket(s, zoneA, 1);
            Event draft = stored(event(venueId, EventStatus.DRAFT, session(s, null, type)));

            handler().delete(ORG, draft.id(), s, type.id());

            verify(events).deleteTicketType(type.id());
        }
    }
}
