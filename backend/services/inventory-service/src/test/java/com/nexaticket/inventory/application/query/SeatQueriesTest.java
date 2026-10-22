// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Đường đọc sơ đồ chỗ và bảng trạng thái cho ban tổ chức. */
class SeatQueriesTest {

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID TYPE = UUID.fromString("00000000-0000-4000-a000-000000000011");

    private final SessionInventoryRepository sessions = mock(SessionInventoryRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SeatQueries queries = new SeatQueries(sessions, seats);

    @BeforeEach
    void setUp() {
        when(sessions.findBySessionId(SESSION))
                .thenReturn(Optional.of(new SessionInventory(
                        UUID.randomUUID(),
                        SESSION,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.parse("2026-10-01T00:00:00Z"),
                        Instant.parse("2026-11-01T00:00:00Z"),
                        new PurchaseLimits(8, 10, 10, 6),
                        Duration.ofMinutes(10))));
        when(sessions.currentAvailabilityVersion(SESSION)).thenReturn(17L);
    }

    @Nested
    @DisplayName("Sơ đồ chỗ")
    class SoDoCho {

        @Test
        @DisplayName("Chép đủ từng trường của ghế và khối vé đứng, kèm version hiện tại")
        void chep_du_truong() {
            UUID seatId = UUID.randomUUID();
            when(seats.seatedRows(SESSION))
                    .thenReturn(List.of(new SeatRepository.SeatRow(
                            seatId,
                            "A-1-1",
                            "A",
                            "Khan dai A",
                            "1",
                            "1",
                            new BigDecimal("10.5"),
                            new BigDecimal("2"),
                            TYPE,
                            "Ve ngoi",
                            1_500_000L,
                            "HELD")));
            when(seats.standingZones(SESSION))
                    .thenReturn(
                            List.of(new SeatRepository.StandingZoneRow("GA", TYPE, "Ve dung", 800_000L, 120, 3000)));

            SeatMapView view = queries.seatMap(SESSION, null);

            assertThat(view.eventSessionId()).isEqualTo(SESSION);
            assertThat(view.availabilityVersion()).isEqualTo(17L);
            assertThat(view.seats())
                    .containsExactly(new SeatMapView.Seat(
                            seatId,
                            "A-1-1",
                            "A",
                            "Khan dai A",
                            "1",
                            "1",
                            new BigDecimal("10.5"),
                            new BigDecimal("2"),
                            TYPE,
                            "Ve ngoi",
                            1_500_000L,
                            "HELD"));
            assertThat(view.standingZones())
                    .containsExactly(new SeatMapView.StandingZone("GA", TYPE, "Ve dung", 800_000L, 120, 3000));
        }

        @Test
        @DisplayName("Khách ẩn danh: không có hạn mức, và không tốn câu đếm nào")
        void an_danh_khong_dem() {
            SeatMapView view = queries.seatMap(SESSION, null);

            assertThat(view.purchaseAllowance()).isNull();
            verify(seats, never()).countUnitsHeldBy(any(), any());
        }

        @Test
        @DisplayName("Khách đã đăng nhập: hạn mức = trần, đã dùng, còn lại")
        void da_dang_nhap_co_han_muc() {
            when(seats.countUnitsHeldBy(SESSION, VIEWER)).thenReturn(4);

            SeatMapView view = queries.seatMap(SESSION, VIEWER);

            assertThat(view.purchaseAllowance()).isEqualTo(new SeatMapView.PurchaseAllowance(6, 4, 2));
        }

        @Test
        @DisplayName("Đã dùng vượt trần (trần bị hạ sau khi mua): còn lại là 0, không âm")
        void con_lai_khong_am() {
            when(seats.countUnitsHeldBy(SESSION, VIEWER)).thenReturn(9);

            assertThat(queries.seatMap(SESSION, VIEWER).purchaseAllowance())
                    .isEqualTo(new SeatMapView.PurchaseAllowance(6, 9, 0));
        }

        @Test
        @DisplayName("Suất chưa dựng tồn kho: SESSION_NOT_FOUND")
        void suat_khong_ton_tai() {
            UUID unknown = UUID.randomUUID();
            when(sessions.findBySessionId(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> queries.seatMap(unknown, VIEWER))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(InventoryErrorCode.SESSION_NOT_FOUND));
            verify(seats, never()).seatedRows(any());
        }
    }

    @Nested
    @DisplayName("Trạng thái theo khu")
    class TrangThaiTheoKhu {

        @Test
        @DisplayName("Chép đủ năm con số của từng khu, giữ thứ tự từ database")
        void chep_du_so_dem() {
            when(seats.zoneStatusCounts(SESSION))
                    .thenReturn(List.of(
                            new SeatRepository.ZoneStatusRow("A", "SEATED", 10, 2, 3, 4, 1),
                            new SeatRepository.ZoneStatusRow("GA", "STANDING", 100, 0, 0, 50, 0)));

            SeatStatusView view = queries.seatStatus(SESSION);

            assertThat(view.availabilityVersion()).isEqualTo(17L);
            assertThat(view.zones())
                    .containsExactly(
                            new SeatStatusView.ZoneStatus("A", "SEATED", 10, 2, 3, 4, 1),
                            new SeatStatusView.ZoneStatus("GA", "STANDING", 100, 0, 0, 50, 0));
        }

        @Test
        @DisplayName("Suất chưa dựng tồn kho: 404, không trả bảng số 0 trông như bán hết")
        void suat_chua_dung() {
            UUID unknown = UUID.randomUUID();
            when(sessions.findBySessionId(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> queries.seatStatus(unknown))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(InventoryErrorCode.SESSION_NOT_FOUND));
            verify(seats, never()).zoneStatusCounts(any());
        }
    }
}
