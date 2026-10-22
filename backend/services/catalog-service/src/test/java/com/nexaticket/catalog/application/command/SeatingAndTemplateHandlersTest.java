// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import static com.nexaticket.catalog.application.command.CatalogFixtures.NOW;
import static com.nexaticket.catalog.application.command.CatalogFixtures.ORG;
import static com.nexaticket.catalog.application.command.CatalogFixtures.assertApiError;
import static com.nexaticket.catalog.application.command.CatalogFixtures.assertClientError;
import static com.nexaticket.catalog.application.command.CatalogFixtures.seatedZone;
import static com.nexaticket.catalog.application.command.CatalogFixtures.venueWithZones;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.LayoutSpecs;
import com.nexaticket.catalog.application.PlatformAccess;
import com.nexaticket.catalog.application.SlugAllocator;
import com.nexaticket.catalog.application.media.PosterUrlPolicy;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Sơ đồ khu (tự dựng và từ khung) và vòng đời khung concert của nền tảng.
 *
 * <p>Trọng tâm là ranh giới giữa <b>lỗi của người gọi</b> (4xx có mã) và <b>lỗi của hệ thống</b>
 * (500): một khu ngồi quên số hàng là lỗi nhập liệu, và người dùng phải nhận được một câu trả lời
 * sửa được.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatingAndTemplateHandlersTest {

    @Mock
    VenueRepository venues;

    @Mock
    EventRepository events;

    @Mock
    ConcertTemplateRepository templates;

    @Mock
    CatalogAccess access;

    @Mock
    PlatformAccess platform;

    @Mock
    PosterUrlPolicy posters;

    private final UUID venueId = UUID.randomUUID();

    private Venue ownVenue() {
        Venue venue = venueWithZones(venueId, seatedZone(venueId, "A"));
        when(venues.findById(ORG, venueId)).thenReturn(Optional.of(venue));
        return venue;
    }

    @Nested
    @DisplayName("Thêm một khu")
    class ThemKhu {

        private AddZoneHandler handler() {
            return new AddZoneHandler(venues, access);
        }

        @Test
        @DisplayName("khu đứng: trả về số chỗ = sức chứa, chưa có bố cục")
        void khu_dung() {
            ownVenue();

            var zone = handler().handle(ORG, venueId, "SAN", "Sàn đứng", "STANDING", null, null, 300, 2);

            assertThat(zone.seatCount()).isEqualTo(300);
            assertThat(zone.kind()).isEqualTo("STANDING");
            verify(venues).addZone(any(VenueZone.class));
        }

        @Test
        @DisplayName("địa điểm của tổ chức khác: 404")
        void dia_diem_khac() {
            UUID foreign = UUID.randomUUID();
            when(venues.findById(ORG, foreign)).thenReturn(Optional.empty());

            assertApiError(
                    () -> handler().handle(ORG, foreign, "A", "A", "SEATED", 1, 1, null, 0),
                    CatalogErrorCode.VENUE_NOT_FOUND);
        }

        @Test
        @DisplayName("loại khu lạ: lỗi có mã, không phải 500")
        void loai_khu_la() {
            ownVenue();

            assertClientError(() -> handler().handle(ORG, venueId, "A", "A", "BALCONY", 1, 1, null, 0));
            verify(venues, never()).addZone(any());
        }

        @Test
        @DisplayName("khu ngồi thiếu số hàng: lỗi 4xx có mã, không phải IllegalArgumentException → 500")
        void khu_ngoi_thieu_so_hang() {
            // ZoneRequest cố ý không kiểm chéo bằng annotation và dựa vào constructor của
            // VenueZone. Constructor ném IllegalArgumentException, handler không bắt, và
            // GlobalExceptionHandler trả 500 cho một lỗi nhập liệu.
            ownVenue();

            assertClientError(() -> handler().handle(ORG, venueId, "A", "A", "SEATED", null, 10, null, 0));
            verify(venues, never()).addZone(any());
        }
    }

    @Nested
    @DisplayName("Thay cả sơ đồ khu của địa điểm")
    class ThaySoDo {

        private ConfigureVenueZonesHandler handler() {
            return new ConfigureVenueZonesHandler(venues, events, access);
        }

        private ConfigureVenueZonesHandler.ZoneSpec seated(String code, Integer rows, Integer seats) {
            return new ConfigureVenueZonesHandler.ZoneSpec(code, "Khu " + code, "SEATED", rows, seats, null, null);
        }

        @Test
        @DisplayName("sơ đồ hợp lệ: thay cả tập, sortOrder mặc định theo thứ tự trong request")
        void thay_ca_tap() {
            ownVenue();
            when(venues.replaceZones(eq(venueId), anyList()))
                    .thenReturn(new VenueRepository.ZoneReplacement(1, 1, 0, 0));

            var result = handler().handle(ORG, venueId, null, List.of(seated("A", 2, 2), seated("VIP", 3, 3)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VenueZone>> saved = ArgumentCaptor.forClass(List.class);
            verify(venues).replaceZones(eq(venueId), saved.capture());
            assertThat(saved.getValue())
                    .extracting(VenueZone::zoneCode, VenueZone::sortOrder)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("A", 0), org.assertj.core.groups.Tuple.tuple("VIP", 1));
            assertThat(result.inserted()).isEqualTo(1);
            verify(venues).updateStage(venueId, null);
        }

        @Test
        @DisplayName("địa điểm dựng từ khung: 409 VENUE_LAYOUT_LOCKED")
        void tu_khung() {
            Venue fromTemplate =
                    new Venue(venueId, ORG, "Arena", "HCM", null, List.of(seatedZone(venueId, "A")), UUID.randomUUID());
            when(venues.findById(ORG, venueId)).thenReturn(Optional.of(fromTemplate));

            assertApiError(
                    () -> handler().handle(ORG, venueId, null, List.of(seated("A", 1, 1))),
                    CatalogErrorCode.VENUE_LAYOUT_LOCKED);
            verify(venues, never()).replaceZones(any(), anyList());
        }

        @Test
        @DisplayName("địa điểm đã có sự kiện từng lên bán: 409 VENUE_IN_USE")
        void dang_dung() {
            ownVenue();
            when(events.hasNonDraftEventAtVenue(venueId)).thenReturn(true);

            assertApiError(
                    () -> handler().handle(ORG, venueId, null, List.of(seated("A", 1, 1))),
                    CatalogErrorCode.VENUE_IN_USE);
        }

        @Test
        @DisplayName("sơ đồ rỗng bị từ chối có mã")
        void so_do_rong() {
            ownVenue();

            assertClientError(() -> handler().handle(ORG, venueId, null, List.of()));
        }

        @Test
        @DisplayName("mã khu trùng nhau: 409, không ghi gì")
        void ma_trung() {
            ownVenue();

            assertClientError(
                    () -> handler().handle(ORG, venueId, null, List.of(seated("A", 1, 1), seated("A", 2, 2))));
            verify(venues, never()).replaceZones(any(), anyList());
        }

        @Test
        @DisplayName("cung quét ngược: 422 ZONE_LAYOUT_INVALID")
        void cung_quet_nguoc() {
            ownVenue();
            var arc = new ConfigureVenueZonesHandler.ZoneSpec(
                    "A",
                    "A",
                    "SEATED",
                    2,
                    2,
                    null,
                    null,
                    new LayoutSpecs.ZoneLayoutSpec("ARC", 0.0, 0.0, null, 5.0, 90.0, 10.0));

            assertApiError(
                    () -> handler().handle(ORG, venueId, null, List.of(arc)), CatalogErrorCode.ZONE_LAYOUT_INVALID);
        }

        @Test
        @DisplayName("sân khấu bề ngang 0: 422 ZONE_LAYOUT_INVALID")
        void san_khau_hong() {
            ownVenue();

            assertApiError(
                    () -> handler()
                            .handle(
                                    ORG,
                                    venueId,
                                    new LayoutSpecs.StageSpec("RECTANGLE", 0.0, 0.0, 0.0, 4.0),
                                    List.of(seated("A", 1, 1))),
                    CatalogErrorCode.ZONE_LAYOUT_INVALID);
        }

        @Test
        @DisplayName("khu ngồi thiếu số ghế mỗi hàng: lỗi 4xx có mã, không phải 500")
        void khu_ngoi_thieu_so_ghe() {
            ownVenue();

            assertClientError(() -> handler().handle(ORG, venueId, null, List.of(seated("A", 10, null))));
            verify(venues, never()).replaceZones(any(), anyList());
        }

        @Test
        @DisplayName("không đủ quyền: không đọc địa điểm")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertApiError(
                    () -> handler().handle(ORG, venueId, null, List.of(seated("A", 1, 1))), ErrorCode.Common.FORBIDDEN);
            verifyNoInteractions(venues);
        }
    }

    @Nested
    @DisplayName("Khung concert — vòng đời")
    class VongDoiKhung {

        private ManageTemplateHandler handler() {
            return new ManageTemplateHandler(templates, platform);
        }

        private ConcertTemplate stored(TemplateStatus status, boolean withZones) {
            ConcertTemplate template = new ConcertTemplate(
                    UUID.randomUUID(),
                    "ARENA",
                    "Arena",
                    "concert",
                    null,
                    status,
                    withZones
                            ? List.of(TemplateZone.create(
                                    UUID.randomUUID(), "VIP", "VIP", AdmissionKind.SEATED, 2, 2, null, 0, 1L))
                            : List.of());
            when(templates.findById(template.id())).thenReturn(Optional.of(template));
            return template;
        }

        @Test
        @DisplayName("tạo: mã được chuẩn hoá in hoa và cắt khoảng trắng trước khi kiểm trùng")
        void tao_chuan_hoa_ma() {
            handler().create("  arena_5k ", "Arena", "concert", null);

            verify(templates).codeExists("ARENA_5K");
            ArgumentCaptor<ConcertTemplate> saved = ArgumentCaptor.forClass(ConcertTemplate.class);
            verify(templates).insert(saved.capture());
            assertThat(saved.getValue().code()).isEqualTo("ARENA_5K");
            assertThat(saved.getValue().status()).isEqualTo(TemplateStatus.DRAFT);
        }

        @Test
        @DisplayName("mã trùng (khác hoa thường): 409 TEMPLATE_CODE_TAKEN")
        void ma_trung() {
            when(templates.codeExists("ARENA")).thenReturn(true);

            assertApiError(
                    () -> handler().create("arena", "Arena", "concert", null), CatalogErrorCode.TEMPLATE_CODE_TAKEN);
            verify(templates, never()).insert(any());
        }

        @Test
        @DisplayName("không phải superadmin: 403 trước mọi thao tác, kể cả xoá")
        void khong_phai_superadmin() {
            doThrow(ApiException.forbidden("Requires SUPER_ADMIN"))
                    .when(platform)
                    .requireSuperAdmin();
            UUID id = UUID.randomUUID();

            assertApiError(() -> handler().create("A", "A", "c", null), ErrorCode.Common.FORBIDDEN);
            assertApiError(() -> handler().activate(id), ErrorCode.Common.FORBIDDEN);
            assertApiError(() -> handler().archive(id), ErrorCode.Common.FORBIDDEN);
            assertApiError(() -> handler().backToDraft(id), ErrorCode.Common.FORBIDDEN);
            assertApiError(() -> handler().delete(id), ErrorCode.Common.FORBIDDEN);
            verifyNoInteractions(templates);
        }

        @Test
        @DisplayName("mở khung rỗng: 409 TEMPLATE_WITHOUT_ZONE, không phải 500 từ IllegalStateException")
        void mo_khung_rong() {
            ConcertTemplate empty = stored(TemplateStatus.DRAFT, false);

            assertApiError(() -> handler().activate(empty.id()), CatalogErrorCode.TEMPLATE_WITHOUT_ZONE);
            verify(templates, never()).update(any());
        }

        @Test
        @DisplayName("mở, lưu trữ, về nháp: mỗi bước lưu trạng thái mới")
        void chuyen_trang_thai() {
            ConcertTemplate template = stored(TemplateStatus.DRAFT, true);

            handler().activate(template.id());
            assertThat(template.status()).isEqualTo(TemplateStatus.ACTIVE);
            handler().archive(template.id());
            assertThat(template.status()).isEqualTo(TemplateStatus.ARCHIVED);
            handler().backToDraft(template.id());
            assertThat(template.status()).isEqualTo(TemplateStatus.DRAFT);
        }

        @Test
        @DisplayName("xoá khung đã có địa điểm dùng: 409 TEMPLATE_IN_USE")
        void xoa_khung_dang_dung() {
            ConcertTemplate template = stored(TemplateStatus.ARCHIVED, true);
            when(templates.hasVenuesFromTemplate(template.id())).thenReturn(true);

            assertApiError(() -> handler().delete(template.id()), CatalogErrorCode.TEMPLATE_IN_USE);
            verify(templates, never()).delete(any());
        }

        @Test
        @DisplayName("xoá khung chưa ai dùng")
        void xoa_khung() {
            ConcertTemplate template = stored(TemplateStatus.DRAFT, false);

            handler().delete(template.id());

            verify(templates).delete(template.id());
        }

        @Test
        @DisplayName("khung không tồn tại: 404")
        void khong_ton_tai() {
            UUID id = UUID.randomUUID();
            when(templates.findById(id)).thenReturn(Optional.empty());

            assertApiError(() -> handler().archive(id), CatalogErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Khung concert — thay tập khu")
    class ThayKhuCuaKhung {

        private ReplaceTemplateZonesHandler handler() {
            return new ReplaceTemplateZonesHandler(templates, platform);
        }

        private UUID stored() {
            ConcertTemplate template = new ConcertTemplate(
                    UUID.randomUUID(), "ARENA", "Arena", "concert", null, TemplateStatus.DRAFT, List.of());
            when(templates.findById(template.id())).thenReturn(Optional.of(template));
            return template.id();
        }

        private ReplaceTemplateZonesHandler.ZoneSpec spec(String code, String kind, Integer rows, Integer cap) {
            return new ReplaceTemplateZonesHandler.ZoneSpec(
                    code, "Khu " + code, kind, rows, rows, cap, null, null, null);
        }

        @Test
        @DisplayName("thay tập khu hợp lệ: trả về chi tiết với sức chứa mới")
        void thay_hop_le() {
            UUID id = stored();

            var detail = handler()
                    .handle(id, null, List.of(spec("VIP", "SEATED", 10, null), spec("SAN", "STANDING", null, 500)));

            assertThat(detail.capacity()).isEqualTo(600);
            verify(templates).replaceZones(any());
            verify(templates).update(any());
        }

        @Test
        @DisplayName("mã khu trùng trong khung: lỗi 4xx có mã, không phải 500")
        void ma_trung() {
            // ConcertTemplate.replaceZones ném IllegalArgumentException với thông điệp nói đúng mã
            // trùng — nhưng handler không bắt, nên thông điệp đó tới người gọi dưới dạng 500.
            UUID id = stored();

            assertClientError(() -> handler()
                    .handle(id, null, List.of(spec("VIP", "SEATED", 2, null), spec("VIP", "SEATED", 3, null))));
            verify(templates, never()).replaceZones(any());
        }

        @Test
        @DisplayName("khu đứng thiếu sức chứa: lỗi 4xx có mã, không phải 500")
        void khu_dung_thieu_suc_chua() {
            UUID id = stored();

            assertClientError(() -> handler().handle(id, null, List.of(spec("SAN", "STANDING", null, null))));
        }

        @Test
        @DisplayName("loại khu lạ: lỗi có mã")
        void loai_khu_la() {
            UUID id = stored();

            assertClientError(() -> handler().handle(id, null, List.of(spec("X", "BOX", 1, null))));
        }
    }

    @Nested
    @DisplayName("Dựng sự kiện từ khung")
    class TuKhung {

        private CreateEventFromTemplateHandler handler() {
            when(posters.validate(any())).thenAnswer(inv -> inv.getArgument(0));
            return new CreateEventFromTemplateHandler(
                    templates, venues, events, access, new SlugAllocator(events), posters);
        }

        private ConcertTemplate template(TemplateStatus status, Long vipPrice) {
            ConcertTemplate template = new ConcertTemplate(
                    UUID.randomUUID(),
                    "ARENA",
                    "Arena",
                    "concert",
                    null,
                    status,
                    List.of(
                            TemplateZone.create(
                                    UUID.randomUUID(), "VIP", "VIP", AdmissionKind.SEATED, 2, 2, null, 0, vipPrice),
                            TemplateZone.create(
                                    UUID.randomUUID(),
                                    "SAN",
                                    "Sàn",
                                    AdmissionKind.STANDING,
                                    null,
                                    null,
                                    100,
                                    1,
                                    200_000L)));
            when(templates.findById(template.id())).thenReturn(Optional.of(template));
            return template;
        }

        private CreateEventFromTemplateHandler.Command command(UUID templateId, Map<String, Long> prices) {
            var startsAt = NOW.plus(Duration.ofDays(20));
            return new CreateEventFromTemplateHandler.Command(
                    templateId,
                    "Đêm nhạc Arena",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Arena Quận 7",
                    "TP.HCM",
                    null,
                    startsAt,
                    null,
                    NOW,
                    startsAt,
                    null,
                    null,
                    null,
                    null,
                    prices);
        }

        @Test
        @DisplayName("giá của tổ chức thắng giá gợi ý; khu không khai giá thì lấy giá gợi ý")
        void gia_to_chuc_thang() {
            ConcertTemplate template = template(TemplateStatus.ACTIVE, 1_000_000L);

            handler().handle(ORG, command(template.id(), Map.of("VIP", 1_500_000L)));

            ArgumentCaptor<TicketType> types = ArgumentCaptor.forClass(TicketType.class);
            verify(events, org.mockito.Mockito.times(2)).addTicketType(types.capture());
            assertThat(types.getAllValues())
                    .extracting(TicketType::priceVnd)
                    .containsExactlyInAnyOrder(1_500_000L, 200_000L);
        }

        @Test
        @DisplayName("khung không ACTIVE: 409 TEMPLATE_NOT_USABLE, không dựng địa điểm")
        void khung_khong_dung_duoc() {
            ConcertTemplate template = template(TemplateStatus.ARCHIVED, 1L);

            assertApiError(
                    () -> handler().handle(ORG, command(template.id(), Map.of())),
                    CatalogErrorCode.TEMPLATE_NOT_USABLE);
            verify(venues, never()).saveWithZones(any(), anyList());
        }

        @Test
        @DisplayName("thiếu giá cho khu không có giá gợi ý: 422 kèm danh sách mã khu")
        void thieu_gia() {
            ConcertTemplate template = template(TemplateStatus.ACTIVE, null);

            assertThatThrownBy(() -> handler().handle(ORG, command(template.id(), Map.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.ZONE_PRICE_REQUIRED);
                        assertThat(e.meta()).containsEntry("zoneCodes", List.of("VIP"));
                    });
            verify(venues, never()).saveWithZones(any(), anyList());
        }

        @Test
        @DisplayName("giá âm: 422, không dựng gì")
        void gia_am() {
            ConcertTemplate template = template(TemplateStatus.ACTIVE, 1L);

            assertApiError(
                    () -> handler().handle(ORG, command(template.id(), Map.of("VIP", -5L))),
                    CatalogErrorCode.ZONE_PRICE_REQUIRED);
            verify(events, never()).insert(any());
        }
    }
}
