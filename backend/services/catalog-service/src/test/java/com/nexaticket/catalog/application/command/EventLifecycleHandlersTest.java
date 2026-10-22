// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import static com.nexaticket.catalog.application.command.CatalogFixtures.NOW;
import static com.nexaticket.catalog.application.command.CatalogFixtures.ORG;
import static com.nexaticket.catalog.application.command.CatalogFixtures.assertApiError;
import static com.nexaticket.catalog.application.command.CatalogFixtures.event;
import static com.nexaticket.catalog.application.command.CatalogFixtures.seatedZone;
import static com.nexaticket.catalog.application.command.CatalogFixtures.session;
import static com.nexaticket.catalog.application.command.CatalogFixtures.ticket;
import static com.nexaticket.catalog.application.command.CatalogFixtures.venueWithZones;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.CatalogProperties;
import com.nexaticket.catalog.application.SlugAllocator;
import com.nexaticket.catalog.application.media.PosterUrlPolicy;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * Các lệnh đổi vòng đời sự kiện: tạo, sửa mô tả, publish, đồng bộ lại, rút xuống, huỷ, xoá nháp.
 *
 * <p>Cổng ra là mock; cửa quyền {@link CatalogAccess} là mock để kiểm được thứ tự "hỏi quyền
 * trước, chạm dữ liệu sau" — một lệnh bị từ chối không được để lại một dòng nào trong repository
 * hay outbox.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventLifecycleHandlersTest {

    @Mock
    EventRepository events;

    @Mock
    VenueRepository venues;

    @Mock
    CatalogAccess access;

    @Mock
    PosterUrlPolicy posters;

    @Mock
    OutboxWriter outbox;

    private final UUID venueId = UUID.randomUUID();
    private VenueZone zoneA;
    private Venue venue;

    @BeforeEach
    void dungDiaDiem() {
        zoneA = seatedZone(venueId, "A");
        venue = venueWithZones(venueId, zoneA);
        when(venues.findById(ORG, venueId)).thenReturn(Optional.of(venue));
        when(posters.validate(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("Tạo sự kiện")
    class TaoSuKien {

        private CreateEventHandler handler() {
            return new CreateEventHandler(events, venues, access, new SlugAllocator(events), posters);
        }

        @Test
        @DisplayName("tạo bản nháp với slug sinh từ tiêu đề, ảnh bìa đã qua chính sách ảnh")
        void tao_ban_nhap() {
            when(posters.validate("https://cdn.example/p.jpg")).thenReturn("https://cdn.example/p.jpg");

            UUID id = handler()
                    .handle(
                            ORG,
                            venueId,
                            "Đêm nhạc Hoà Âm",
                            null,
                            null,
                            null,
                            "nhac-song",
                            "https://cdn.example/p.jpg");

            ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
            verify(events).insert(saved.capture());
            assertThat(saved.getValue().id()).isEqualTo(id);
            assertThat(saved.getValue().status()).isEqualTo(EventStatus.DRAFT);
            assertThat(saved.getValue().slug().value()).isEqualTo("dem-nhac-hoa-am");
            assertThat(saved.getValue().organizationId()).isEqualTo(ORG);
            verify(posters).validate("https://cdn.example/p.jpg");
        }

        @Test
        @DisplayName("địa điểm của tổ chức khác (hoặc không tồn tại): 404, không ghi gì")
        void dia_diem_cua_to_chuc_khac() {
            UUID foreignVenue = UUID.randomUUID();
            when(venues.findById(ORG, foreignVenue)).thenReturn(Optional.empty());

            assertApiError(
                    () -> handler().handle(ORG, foreignVenue, "Tiêu đề", null, null, null, "nhac", null),
                    CatalogErrorCode.VENUE_NOT_FOUND);
            verify(events, never()).insert(any());
        }

        @Test
        @DisplayName("không đủ quyền: dừng trước khi đọc địa điểm")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertApiError(
                    () -> handler().handle(ORG, venueId, "Tiêu đề", null, null, null, "nhac", null),
                    ErrorCode.Common.FORBIDDEN);
            verifyNoInteractions(venues, events);
        }

        @Test
        @DisplayName("ảnh bìa bị chính sách từ chối thì không có sự kiện nào được lưu")
        void anh_bia_bi_tu_choi() {
            when(posters.validate("http://evil.example/x.jpg"))
                    .thenThrow(new ApiException(CatalogErrorCode.POSTER_URL_INVALID, "https"));

            assertApiError(
                    () -> handler()
                            .handle(ORG, venueId, "Tiêu đề", null, null, null, "nhac", "http://evil.example/x.jpg"),
                    CatalogErrorCode.POSTER_URL_INVALID);
            verify(events, never()).insert(any());
        }
    }

    @Nested
    @DisplayName("Sửa mô tả")
    class SuaMoTa {

        @Test
        @DisplayName("sửa được cả khi đang bán; chuỗi rỗng ở ảnh bìa nghĩa là xoá ảnh")
        void sua_khi_dang_ban() {
            Event published = event(venueId, EventStatus.PUBLISHED);
            when(events.findByIdForOrganization(ORG, published.id())).thenReturn(Optional.of(published));

            new UpdateEventHandler(events, access, posters)
                    .handle(ORG, published.id(), "Tên mới", null, null, null, "");

            verify(events).update(published);
            assertThat(published.title()).isEqualTo("Tên mới");
            assertThat(published.posterUrl()).isEmpty();
            assertThat(published.status()).isEqualTo(EventStatus.PUBLISHED);
        }

        @Test
        @DisplayName("sự kiện không thuộc tổ chức: 404")
        void khong_thuoc_to_chuc() {
            UUID eventId = UUID.randomUUID();
            when(events.findByIdForOrganization(ORG, eventId)).thenReturn(Optional.empty());

            assertApiError(
                    () -> new UpdateEventHandler(events, access, posters)
                            .handle(ORG, eventId, "x", null, null, null, null),
                    CatalogErrorCode.EVENT_NOT_FOUND);
            verify(events, never()).update(any());
        }
    }

    @Nested
    @DisplayName("Publish")
    class Publish {

        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final CatalogProperties properties = new CatalogProperties(4, 6, 6, 10, 500, false, null);

        private PublishEventHandler handler() {
            return new PublishEventHandler(events, venues, outbox, access, properties, clock);
        }

        @Test
        @DisplayName("đủ điều kiện: PUBLISHED với mốc của đồng hồ, mỗi suất một message outbox")
        void publish_thanh_cong() {
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            Event draft = event(
                    venueId,
                    EventStatus.DRAFT,
                    session(s1, null, ticket(s1, zoneA, 500_000)),
                    session(s2, null, ticket(s2, zoneA, 300_000)));
            when(events.findByIdForOrganization(ORG, draft.id())).thenReturn(Optional.of(draft));

            handler().handle(ORG, draft.id());

            assertThat(draft.status()).isEqualTo(EventStatus.PUBLISHED);
            assertThat(draft.publishedAt()).isEqualTo(NOW);
            verify(events).update(draft);
            verify(outbox).append(eq("nexaticket.catalog"), eq("EventSession"), eq(s1), eq("session.published"), any());
            verify(outbox).append(eq("nexaticket.catalog"), eq("EventSession"), eq(s2), eq("session.published"), any());
        }

        @Test
        @DisplayName("chưa có suất: 409 PUBLISH_BLOCKED kèm danh sách vướng mắc, không đổi trạng thái")
        void chua_co_suat() {
            Event draft = event(venueId, EventStatus.DRAFT);
            when(events.findByIdForOrganization(ORG, draft.id())).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> handler().handle(ORG, draft.id()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.PUBLISH_BLOCKED);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(409);
                        assertThat(e.meta()).containsEntry("blockers", List.of("NO_SESSION"));
                    });
            assertThat(draft.status()).isEqualTo(EventStatus.DRAFT);
            verify(events, never()).update(any());
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("có suất không bán gì và địa điểm không có khu: trả về HẾT vướng mắc")
        void nhieu_vuong_mac() {
            UUID emptyVenueId = UUID.randomUUID();
            when(venues.findById(ORG, emptyVenueId)).thenReturn(Optional.of(venueWithZones(emptyVenueId)));
            Event draft = event(emptyVenueId, EventStatus.DRAFT, session(UUID.randomUUID(), null));
            when(events.findByIdForOrganization(ORG, draft.id())).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> handler().handle(ORG, draft.id()))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.meta().get("blockers"))
                                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                                    .containsExactlyInAnyOrder("VENUE_WITHOUT_ZONE", "SESSION_WITHOUT_TICKET_TYPE"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"PUBLISHED", "CANCELLED"})
        @DisplayName("đang bán hoặc đã huỷ thì không publish được: 409 INVALID_EVENT_STATE")
        void trang_thai_khong_publish_duoc(EventStatus status) {
            UUID s = UUID.randomUUID();
            Event event = event(venueId, status, session(s, null, ticket(s, zoneA, 1)));
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            assertApiError(() -> handler().handle(ORG, event.id()), CatalogErrorCode.INVALID_EVENT_STATE);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("publish lại sau khi rút xuống: được, và phát lại message cho mọi suất")
        void publish_lai() {
            UUID s = UUID.randomUUID();
            Event unpublished = event(venueId, EventStatus.UNPUBLISHED, session(s, null, ticket(s, zoneA, 1)));
            when(events.findByIdForOrganization(ORG, unpublished.id())).thenReturn(Optional.of(unpublished));

            handler().handle(ORG, unpublished.id());

            assertThat(unpublished.status()).isEqualTo(EventStatus.PUBLISHED);
            verify(outbox, times(1)).append(anyString(), anyString(), eq(s), anyString(), any());
        }

        @Test
        @DisplayName("sự kiện không thuộc tổ chức: 404 trước khi chạm địa điểm")
        void khong_thuoc_to_chuc() {
            UUID eventId = UUID.randomUUID();
            when(events.findByIdForOrganization(ORG, eventId)).thenReturn(Optional.empty());

            assertApiError(() -> handler().handle(ORG, eventId), CatalogErrorCode.EVENT_NOT_FOUND);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("không đủ quyền: không đọc, không phát gì")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertApiError(() -> handler().handle(ORG, UUID.randomUUID()), ErrorCode.Common.FORBIDDEN);
            verifyNoInteractions(events, outbox);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"DRAFT", "UNPUBLISHED", "CANCELLED"})
        @DisplayName("đồng bộ lại chỉ cho sự kiện đang bán — nếu không là publish lén không qua preflight")
        void dong_bo_lai_chi_khi_dang_ban(EventStatus status) {
            UUID s = UUID.randomUUID();
            Event event = event(venueId, status, session(s, null, ticket(s, zoneA, 1)));
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            assertApiError(() -> handler().resyncInventory(ORG, event.id()), CatalogErrorCode.INVALID_EVENT_STATE);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("đồng bộ lại sự kiện đang bán: phát lại mọi suất, không đổi trạng thái")
        void dong_bo_lai() {
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            Event published = event(
                    venueId,
                    EventStatus.PUBLISHED,
                    session(s1, null, ticket(s1, zoneA, 1)),
                    session(s2, null, ticket(s2, zoneA, 1)));
            when(events.findByIdForOrganization(ORG, published.id())).thenReturn(Optional.of(published));

            int resent = handler().resyncInventory(ORG, published.id());

            assertThat(resent).isEqualTo(2);
            verify(events, never()).update(any());
            verify(outbox, times(2)).append(anyString(), anyString(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Rút xuống, huỷ, xoá")
    class RutHuyXoa {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"DRAFT", "UNPUBLISHED", "CANCELLED"})
        @DisplayName("rút xuống sự kiện không đang bán: 409 EVENT_NOT_PUBLISHED")
        void rut_xuong_khi_khong_ban(EventStatus status) {
            Event event = event(venueId, status);
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            assertApiError(
                    () -> new UnpublishEventHandler(events, access).handle(ORG, event.id()),
                    CatalogErrorCode.EVENT_NOT_PUBLISHED);
            verify(events, never()).update(any());
        }

        @Test
        @DisplayName("rút xuống sự kiện đang bán: UNPUBLISHED và được lưu")
        void rut_xuong() {
            Event event = event(venueId, EventStatus.PUBLISHED);
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            new UnpublishEventHandler(events, access).handle(ORG, event.id());

            assertThat(event.status()).isEqualTo(EventStatus.UNPUBLISHED);
            verify(events).update(event);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"DRAFT", "PUBLISHED", "UNPUBLISHED"})
        @DisplayName("huỷ được từ mọi trạng thái chưa huỷ, không xoá gì")
        void huy(EventStatus status) {
            Event event = event(venueId, status);
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            new CancelEventHandler(events, access).cancel(ORG, event.id());

            assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
            verify(events).update(event);
            verify(events, never()).deleteEvent(any());
        }

        @Test
        @DisplayName("huỷ lần hai: 409, không phải 500 từ IllegalStateException của domain")
        void huy_hai_lan() {
            Event event = event(venueId, EventStatus.CANCELLED);
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            assertApiError(
                    () -> new CancelEventHandler(events, access).cancel(ORG, event.id()),
                    CatalogErrorCode.INVALID_EVENT_STATE);
        }

        @Test
        @DisplayName("xoá bản nháp: xoá hẳn")
        void xoa_ban_nhap() {
            Event event = event(venueId, EventStatus.DRAFT);
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            new CancelEventHandler(events, access).deleteDraft(ORG, event.id());

            verify(events).deleteEvent(event.id());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"PUBLISHED", "UNPUBLISHED", "CANCELLED"})
        @DisplayName("đã từng lên bán thì không xoá được: 409")
        void khong_xoa_khi_da_ban(EventStatus status) {
            Event event = event(venueId, status);
            when(events.findByIdForOrganization(ORG, event.id())).thenReturn(Optional.of(event));

            assertApiError(
                    () -> new CancelEventHandler(events, access).deleteDraft(ORG, event.id()),
                    CatalogErrorCode.INVALID_EVENT_STATE);
            verify(events, never()).deleteEvent(any());
        }

        @Test
        @DisplayName("xoá sự kiện của tổ chức khác: 404")
        void xoa_cua_to_chuc_khac() {
            UUID eventId = UUID.randomUUID();
            when(events.findByIdForOrganization(ORG, eventId)).thenReturn(Optional.empty());

            assertApiError(
                    () -> new CancelEventHandler(events, access).deleteDraft(ORG, eventId),
                    CatalogErrorCode.EVENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Slug")
    class SlugCuaSuKien {

        @Test
        @DisplayName("slug tự gõ đã có người dùng: 409, không lặng lẽ thêm hậu tố")
        void slug_tu_go_bi_trung() {
            when(events.slugExists(new Slug("hoa-am-2026"))).thenReturn(true);

            assertApiError(
                    () -> new SlugAllocator(events).allocate("hoa-am-2026", "Hoà Âm"),
                    CatalogErrorCode.SLUG_ALREADY_TAKEN);
        }

        @Test
        @DisplayName("slug sinh từ tiêu đề bị trùng thì thêm hậu tố -2, -3…")
        void slug_sinh_ra_bi_trung() {
            when(events.slugExists(new Slug("hoa-am"))).thenReturn(true);
            when(events.slugExists(new Slug("hoa-am-2"))).thenReturn(true);

            assertThat(new SlugAllocator(events).allocate(null, "Hoà Âm").value())
                    .isEqualTo("hoa-am-3");
        }

        @Test
        @DisplayName("hết 20 lần thử: 409 thay vì lặp mãi")
        void het_lan_thu() {
            when(events.slugExists(any())).thenReturn(true);

            assertApiError(
                    () -> new SlugAllocator(events).allocate("  ", "Hoà Âm"), CatalogErrorCode.SLUG_ALREADY_TAKEN);
        }

        @Test
        @DisplayName("slug tự gõ sai định dạng: lỗi 4xx có mã, không phải 500")
        void slug_tu_go_sai_dinh_dang() {
            // EventRequest chỉ chặn @Size(max = 96). "Hoa Am!" đi thẳng tới `new Slug(...)`, ném
            // IllegalArgumentException, và GlobalExceptionHandler biến nó thành 500.
            CatalogFixtures.assertClientError(() -> new SlugAllocator(events).allocate("Hoa Am!", "Hoà Âm"));
        }

        @Test
        @DisplayName("tiêu đề không sinh được slug (toàn ký hiệu): lỗi 4xx có mã, không phải 500")
        void tieu_de_khong_sinh_duoc_slug() {
            // @NotBlank cho qua "!!!", Slug.from ném IllegalArgumentException → 500.
            CatalogFixtures.assertClientError(() -> new SlugAllocator(events).allocate(null, "!!!"));
        }
    }
}
