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
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Luật gán nhân viên soát vé: ai được soát suất nào.
 *
 * <p>Phần đọc vai trò từ token đăng nhập ({@code requireScanner}) được kiểm qua HTTP ở
 * {@code CheckinControllerWebTest}; ở đây là phần luật đã được tách ra để kiểm thuần.
 */
class CheckinAccessTest {

    private static final UUID ORG = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID STAFF = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID EVENT = UUID.fromString("30000000-0000-4000-8000-000000000004");

    private CheckinAssignmentRepository assignments;
    private CatalogPort catalog;
    private CheckinAccess access;

    @BeforeEach
    void setUp() {
        assignments = mock(CheckinAssignmentRepository.class);
        catalog = mock(CatalogPort.class);
        // Ca trực (mobile-v2) chỉ dùng ở requireScannerOnShift; các ca ở đây kiểm luật gán nên giả là đủ.
        access = new CheckinAccess(
                assignments,
                catalog,
                mock(CheckinShifts.class),
                Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("Người quản lý sự kiện của tổ chức")
    class NguoiQuanLy {

        @Test
        @DisplayName("Không cần được gán, và không tốn một lời gọi sang catalog")
        void khong_can_gan() {
            assertThatCode(() -> access.requireAssigned(true, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
            verifyNoInteractions(catalog, assignments);
        }
    }

    @Nested
    @DisplayName("Nhân viên soát vé")
    class NhanVien {

        @Test
        @DisplayName("Được gán vào SỰ KIỆN chứa suất này: cho qua")
        void duoc_gan_su_kien() {
            givenSessionBelongsTo(EVENT);
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(true);

            assertThatCode(() -> access.requireAssigned(false, ORG, STAFF, SESSION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Không được gán sự kiện này: 403 NOT_YOUR_EVENT")
        void khong_duoc_gan() {
            givenSessionBelongsTo(EVENT);
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(false);

            assertThatThrownBy(() -> access.requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(TicketingErrorCode.NOT_YOUR_EVENT);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Kiểm việc gán theo id sự kiện, không nhầm với id suất diễn")
        void kiem_theo_su_kien_khong_theo_suat() {
            givenSessionBelongsTo(EVENT);
            when(assignments.isAssigned(ORG, STAFF, SESSION)).thenReturn(true);

            assertThatThrownBy(() -> access.requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("Catalog không trả lời: KHÔNG cho qua — mất câu trả lời 'có được phép không'")
        void catalog_im_lang_thi_tu_choi() {
            when(catalog.sessionInfo(SESSION)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> access.requireAssigned(false, ORG, STAFF, SESSION))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.NOT_YOUR_EVENT));
            verify(assignments, never()).isAssigned(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Đặt lại danh sách gán")
    class DatLai {

        @Test
        @DisplayName("Ghi kèm người gán và thời điểm theo đồng hồ, rồi trả lại trạng thái sau khi ghi")
        void dat_lai_va_doc_lai() {
            Instant now = Instant.parse("2026-10-01T03:00:00Z");
            UUID admin = UUID.randomUUID();
            CheckinAssignments service =
                    new CheckinAssignments(assignments, catalog, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(6));
            when(assignments.find(ORG, STAFF))
                    .thenReturn(List.of(new CheckinAssignmentRepository.Assignment(STAFF, EVENT, admin, now)));

            List<CheckinAssignments.Assigned> result = service.replace(ORG, STAFF, List.of(EVENT), admin);

            verify(assignments).replace(ORG, STAFF, List.of(EVENT), admin, now);
            assertThat(result).containsExactly(new CheckinAssignments.Assigned(STAFF, EVENT));
        }
    }

    private void givenSessionBelongsTo(UUID eventId) {
        when(catalog.sessionInfo(SESSION))
                .thenReturn(Optional.of(
                        new CatalogPort.SessionInfo(eventId, "Sự kiện", Instant.parse("2026-10-12T13:00:00Z"), null)));
    }
}
