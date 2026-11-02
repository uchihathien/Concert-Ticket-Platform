// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.model.AssignmentWindow;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository.Assignment;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository.Scope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Luật phân công soát vé: ai được soát suất nào, và tới khi nào.
 *
 * <p>Phần đọc vai trò từ token đăng nhập ({@code requireScanner}) được kiểm qua HTTP ở
 * {@code CheckinControllerWebTest}; ở đây là phần luật đã được tách ra để kiểm thuần.
 */
class CheckinAccessTest {

    private static final UUID ORG = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("30000000-0000-4000-8000-000000000009");
    private static final UUID STAFF = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID LATER_SESSION = UUID.fromString("30000000-0000-4000-8000-000000000005");
    private static final UUID EVENT = UUID.fromString("30000000-0000-4000-8000-000000000004");

    /** Suất 1: 13:00–16:00 ngày 12/10. Suất 2: 13:00–16:00 ngày 13/10. */
    private static final Instant S1_START = Instant.parse("2026-10-12T13:00:00Z");

    private static final Instant S2_START = Instant.parse("2026-10-13T13:00:00Z");
    private static final Instant S1_END = S1_START.plusSeconds(3 * 3600);
    private static final Instant S2_END = S2_START.plusSeconds(3 * 3600);

    private CheckinAssignmentRepository assignments;
    private CatalogPort catalog;

    @BeforeEach
    void setUp() {
        assignments = mock(CheckinAssignmentRepository.class);
        catalog = mock(CatalogPort.class);
    }

    private CheckinAccess accessAt(Instant now) {
        return new CheckinAccess(assignments, catalog, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static CatalogPort.EventSchedule schedule(UUID organization) {
        return new CatalogPort.EventSchedule(
                EVENT,
                organization,
                "Đêm nhạc",
                "Nhà hát",
                List.of(
                        new AssignmentWindow.Session(SESSION, S1_START, S1_END),
                        new AssignmentWindow.Session(LATER_SESSION, S2_START, S2_END)));
    }

    private void givenSessionBelongsTo(UUID eventId) {
        when(catalog.sessionInfo(SESSION))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(eventId, "Đêm nhạc", S1_START, S1_END)));
        when(catalog.eventSchedules(List.of(eventId))).thenReturn(Map.of(eventId, schedule(ORG)));
    }

    private void givenAssigned(UUID eventSessionId) {
        when(assignments.forUserEvent(ORG, STAFF, EVENT))
                .thenReturn(List.of(new Assignment(UUID.randomUUID(), STAFF, EVENT, eventSessionId, null, S1_START)));
    }

    @Nested
    @DisplayName("Chủ tổ chức / quản lý sự kiện")
    class NguoiQuanLy {

        @Test
        @DisplayName("Quét bình thường dù không được gán, kể cả sau khi mọi suất đã qua; không tốn lời gọi catalog")
        void khong_can_gan() {
            assertThatCode(() -> accessAt(S2_END.plusSeconds(86_400)).requireAssigned(true, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
            verifyNoInteractions(catalog, assignments);
        }
    }

    @Nested
    @DisplayName("Thành viên vai Staff")
    class NhanVien {

        @Test
        @DisplayName("Được gán cả SỰ KIỆN chứa suất này, còn hạn: cho qua")
        void duoc_gan_su_kien() {
            givenSessionBelongsTo(EVENT);
            givenAssigned(null);

            assertThatCode(() -> accessAt(S1_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Được gán đúng SUẤT này: cho qua")
        void duoc_gan_suat() {
            givenSessionBelongsTo(EVENT);
            givenAssigned(SESSION);

            assertThatCode(() -> accessAt(S1_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Chỉ được gán suất KHÁC của cùng sự kiện: 403 NOT_YOUR_EVENT")
        void gan_suat_khac() {
            givenSessionBelongsTo(EVENT);
            givenAssigned(LATER_SESSION);

            assertThatThrownBy(() -> accessAt(S1_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.NOT_YOUR_EVENT));
        }

        @Test
        @DisplayName("Quét sự kiện không được gán: 403 NOT_YOUR_EVENT")
        void khong_duoc_gan() {
            givenSessionBelongsTo(EVENT);
            when(assignments.forUserEvent(ORG, STAFF, EVENT)).thenReturn(List.of());

            assertThatThrownBy(() -> accessAt(S1_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(TicketingErrorCode.NOT_YOUR_EVENT);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Gán cả sự kiện: còn hạn tới 6 giờ sau khi SUẤT CUỐI kết thúc")
        void han_tinh_theo_suat_cuoi() {
            givenSessionBelongsTo(EVENT);
            givenAssigned(null);

            // Suất 1 đã xong từ hôm qua nhưng suất 2 chưa xong: vẫn còn hạn.
            assertThatCode(() -> accessAt(S2_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
            assertThatCode(() -> accessAt(S2_END.plusSeconds(6 * 3600)).requireAssigned(false, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Quét sau khi hết hạn (suất cuối xong quá 6 giờ): 403 CHECKIN_ASSIGNMENT_EXPIRED")
        void het_han() {
            givenSessionBelongsTo(EVENT);
            givenAssigned(null);

            assertThatThrownBy(() ->
                            accessAt(S2_END.plusSeconds(6 * 3600 + 1)).requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(TicketingErrorCode.CHECKIN_ASSIGNMENT_EXPIRED);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Gán riêng suất 1: hết hạn theo suất 1, không chờ suất 2")
        void het_han_theo_suat() {
            givenSessionBelongsTo(EVENT);
            givenAssigned(SESSION);

            assertThatThrownBy(() ->
                            accessAt(S1_END.plusSeconds(6 * 3600 + 1)).requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.CHECKIN_ASSIGNMENT_EXPIRED));
        }

        @Test
        @DisplayName("Catalog không trả lời: KHÔNG cho qua — mất câu trả lời 'có được phép không'")
        void catalog_im_lang_thi_tu_choi() {
            when(catalog.sessionInfo(SESSION)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accessAt(S1_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.NOT_YOUR_EVENT));
            verify(assignments, never()).forUserEvent(any(), any(), any());
        }

        @Test
        @DisplayName("Đọc được suất nhưng không đọc được lịch để tính hạn: KHÔNG cho qua")
        void khong_doc_duoc_lich() {
            when(catalog.sessionInfo(SESSION))
                    .thenReturn(Optional.of(new CatalogPort.SessionInfo(EVENT, "Đêm nhạc", S1_START, S1_END)));
            givenAssigned(null);
            when(catalog.eventSchedules(List.of(EVENT)))
                    .thenThrow(new CatalogPort.CatalogUnavailableException("down", null));

            assertThatThrownBy(() -> accessAt(S1_START).requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.NOT_YOUR_EVENT));
        }
    }

    @Nested
    @DisplayName("Gán phân công")
    class Gan {

        private final UUID admin = UUID.randomUUID();
        private final Instant now = S1_START.minusSeconds(86_400);

        private CheckinAssignments service(Instant at) {
            return new CheckinAssignments(assignments, catalog, Clock.fixed(at, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("Thêm phạm vi thiếu kèm người gán và giờ theo đồng hồ; phạm vi đã có không ghi lại")
        void them_pham_vi_thieu() {
            when(catalog.eventSchedules(any())).thenReturn(Map.of(EVENT, schedule(ORG)));
            Assignment existing = new Assignment(UUID.randomUUID(), STAFF, EVENT, null, admin, now);
            when(assignments.find(ORG, STAFF)).thenReturn(List.of(existing));

            service(now)
                    .assign(
                            ORG,
                            STAFF,
                            List.of(
                                    new CheckinAssignments.Target(EVENT, null),
                                    new CheckinAssignments.Target(EVENT, SESSION)),
                            admin);

            verify(assignments).delete(ORG, STAFF, List.of());
            verify(assignments).insert(ORG, STAFF, List.of(new Scope(EVENT, SESSION)), admin, now);
        }

        @Test
        @DisplayName("Sự kiện của tổ chức khác: 400 EVENT_NOT_IN_ORGANIZATION, không ghi gì")
        void su_kien_to_chuc_khac() {
            when(catalog.eventSchedules(any())).thenReturn(Map.of(EVENT, schedule(OTHER_ORG)));
            when(assignments.find(ORG, STAFF)).thenReturn(List.of());

            assertThatThrownBy(() ->
                            service(now).assign(ORG, STAFF, List.of(new CheckinAssignments.Target(EVENT, null)), admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.EVENT_NOT_IN_ORGANIZATION));
            verify(assignments, never()).insert(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Suất không thuộc sự kiện: 400 EVENT_NOT_IN_ORGANIZATION")
        void suat_la() {
            when(catalog.eventSchedules(any())).thenReturn(Map.of(EVENT, schedule(ORG)));
            when(assignments.find(ORG, STAFF)).thenReturn(List.of());

            assertThatThrownBy(() -> service(now)
                            .assign(
                                    ORG,
                                    STAFF,
                                    List.of(new CheckinAssignments.Target(EVENT, UUID.randomUUID())),
                                    admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.EVENT_NOT_IN_ORGANIZATION));
        }

        @Test
        @DisplayName("Danh sách rỗng gỡ phân công CÒN HIỆU LỰC, giữ phân công đã hết hạn làm lịch sử")
        void giu_lich_su() {
            UUID pastEvent = UUID.randomUUID();
            CatalogPort.EventSchedule past = new CatalogPort.EventSchedule(
                    pastEvent,
                    ORG,
                    "Đêm cũ",
                    "Nhà hát",
                    List.of(new AssignmentWindow.Session(UUID.randomUUID(), now.minusSeconds(86_400 * 10), null)));
            when(catalog.eventSchedules(any())).thenReturn(Map.of(EVENT, schedule(ORG), pastEvent, past));
            Assignment active = new Assignment(UUID.randomUUID(), STAFF, EVENT, null, admin, now);
            Assignment expired = new Assignment(UUID.randomUUID(), STAFF, pastEvent, null, admin, now);
            when(assignments.find(ORG, STAFF)).thenReturn(List.of(active, expired));

            service(now).assign(ORG, STAFF, List.of(), admin);

            verify(assignments).delete(ORG, STAFF, List.of(active.id()));
        }

        @Test
        @DisplayName("Gỡ khỏi tổ chức: chỉ gỡ phân công còn hiệu lực; catalog im lặng thì gỡ hết cho chắc")
        void go_khoi_to_chuc() {
            UUID pastEvent = UUID.randomUUID();
            Assignment active = new Assignment(UUID.randomUUID(), STAFF, EVENT, null, admin, now);
            Assignment expired = new Assignment(UUID.randomUUID(), STAFF, pastEvent, null, admin, now);
            when(assignments.find(ORG, STAFF)).thenReturn(List.of(active, expired));
            when(catalog.eventSchedules(any()))
                    .thenReturn(Map.of(
                            EVENT,
                            schedule(ORG),
                            pastEvent,
                            new CatalogPort.EventSchedule(
                                    pastEvent,
                                    ORG,
                                    "Đêm cũ",
                                    null,
                                    List.of(new AssignmentWindow.Session(
                                            UUID.randomUUID(), now.minusSeconds(86_400 * 10), null)))));

            service(now).revokeActive(ORG, STAFF);
            verify(assignments).delete(ORG, STAFF, List.of(active.id()));

            when(catalog.eventSchedules(any())).thenThrow(new CatalogPort.CatalogUnavailableException("down", null));
            service(now).revokeActive(ORG, STAFF);
            verify(assignments).delete(ORG, STAFF, List.of(active.id(), expired.id()));
        }

        @Test
        @DisplayName("Đọc phân công: trạng thái sắp tới / đang diễn ra / đã hết hạn theo đồng hồ")
        void trang_thai() {
            when(catalog.eventSchedules(any())).thenReturn(Map.of(EVENT, schedule(ORG)));
            when(assignments.find(ORG, STAFF))
                    .thenReturn(List.of(new Assignment(UUID.randomUUID(), STAFF, EVENT, null, admin, now)));

            assertThat(service(now).list(ORG, STAFF).get(0).status()).isEqualTo("UPCOMING");
            assertThat(service(S1_START).list(ORG, STAFF).get(0).status()).isEqualTo("ONGOING");
            assertThat(service(S2_END.plusSeconds(7 * 3600))
                            .list(ORG, STAFF)
                            .get(0)
                            .status())
                    .isEqualTo("EXPIRED");
            assertThat(service(S2_END.plusSeconds(7 * 3600)).activeOf(ORG, STAFF))
                    .isEmpty();
            assertThat(service(now).list(ORG, STAFF).get(0).expiresAt()).isEqualTo(S2_END.plusSeconds(6 * 3600));
        }
    }
}
