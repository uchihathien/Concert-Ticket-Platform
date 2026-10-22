// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.HoldStatus;
import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Đường giữ chỗ với mọi cổng được thay bằng mock.
 *
 * <p>Không chứng minh chống oversell — việc đó là của database và đã có IT. Ở đây kiểm <b>thứ tự</b>
 * các bước, việc từ chối sớm trước khi chạm khoá, và việc trả khoá Redis đúng lúc transaction
 * rollback. Đồng bộ hoá transaction được bật tay để mô phỏng commit / rollback mà không cần
 * database.
 */
class PlaceHoldHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID SEAT_1 = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID SEAT_2 = UUID.fromString("00000000-0000-4000-a000-000000000002");
    private static final UUID SEAT_3 = UUID.fromString("00000000-0000-4000-a000-000000000003");
    private static final long VERSION = 42L;

    private final SessionInventoryRepository sessions = mock(SessionInventoryRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    private final AvailabilityGate gate = mock(AvailabilityGate.class);
    private final AvailabilityPublisher publisher = mock(AvailabilityPublisher.class);

    private final PlaceHoldHandler handler =
            new PlaceHoldHandler(sessions, seats, holds, gate, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        givenSession(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(1)));
        when(sessions.bumpAvailabilityVersion(SESSION)).thenReturn(VERSION);
        when(gate.tryAcquire(eq(SESSION), anyList(), any(), eq(USER), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("Đường thành công")
    class DuongThanhCong {

        @Test
        @DisplayName("Ghế đích danh: qua cổng Redis với TTL của suất, ghi database, lưu giữ chỗ ACTIVE")
        void ghe_dich_danh() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1, SEAT_2), USER)).thenReturn(2);

            var result = handler.handle(command(List.of(SEAT_1, SEAT_2), List.of(), List.of()));

            ArgumentCaptor<UUID> holdIdAtGate = ArgumentCaptor.forClass(UUID.class);
            verify(gate)
                    .tryAcquire(eq(SESSION), eq(List.of(SEAT_1, SEAT_2)), holdIdAtGate.capture(), eq(USER), eq(600));
            SeatHold saved = savedHold();
            assertThat(saved.id()).isEqualTo(result.holdId()).isEqualTo(holdIdAtGate.getValue());
            assertThat(saved.status()).isEqualTo(HoldStatus.ACTIVE);
            assertThat(saved.userId()).isEqualTo(USER);
            assertThat(saved.seatIds()).containsExactly(SEAT_1, SEAT_2);
            assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
            assertThat(result.seatIds()).containsExactly(SEAT_1, SEAT_2);
            assertThat(result.availabilityVersion()).isEqualTo(VERSION);
        }

        @Test
        @DisplayName("Chỉ vé đứng: không chạm cổng Redis, cấp phát theo số lượng đã gộp")
        void chi_ve_dung() {
            when(seats.allocateStanding(SESSION, "GA", 3, USER)).thenReturn(List.of(SEAT_1, SEAT_2, SEAT_3));

            var result = handler.handle(command(List.of(), List.of(), List.of(standing("GA", 1), standing("GA", 2))));

            verifyNoInteractions(gate);
            verify(seats, never()).holdSeated(any(), anyList(), any());
            assertThat(result.seatIds()).containsExactly(SEAT_1, SEAT_2, SEAT_3);
        }

        @Test
        @DisplayName("Chỉ vé ngồi theo khu: không chạm cổng Redis — chưa có mã ghế nào để từ chối")
        void ve_ngoi_theo_khu() {
            when(seats.allocateSeatedInZone(SESSION, "A", 2, USER)).thenReturn(List.of(SEAT_1, SEAT_2));

            var result = handler.handle(command(List.of(), List.of(zone("A", 2)), List.of()));

            verifyNoInteractions(gate);
            assertThat(result.seatIds()).containsExactly(SEAT_1, SEAT_2);
        }

        @Test
        @DisplayName("Hỗn hợp: ghế đích danh, rồi vé theo khu, rồi vé đứng; bump version ở bước cuối")
        void hon_hop_dung_thu_tu() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1), USER)).thenReturn(1);
            when(seats.allocateSeatedInZone(SESSION, "A", 1, USER)).thenReturn(List.of(SEAT_2));
            when(seats.allocateStanding(SESSION, "GA", 1, USER)).thenReturn(List.of(SEAT_3));

            var result = handler.handle(command(List.of(SEAT_1), List.of(zone("A", 1)), List.of(standing("GA", 1))));

            InOrder order = inOrder(seats, gate, holds, sessions);
            order.verify(seats).lockCustomerSession(USER, SESSION);
            order.verify(seats).countUnitsHeldBy(SESSION, USER);
            order.verify(gate).tryAcquire(eq(SESSION), eq(List.of(SEAT_1)), any(), eq(USER), anyInt());
            order.verify(seats).holdSeated(SESSION, List.of(SEAT_1), USER);
            order.verify(seats).allocateSeatedInZone(SESSION, "A", 1, USER);
            order.verify(seats).allocateStanding(SESSION, "GA", 1, USER);
            order.verify(holds).save(any());
            order.verify(sessions).bumpAvailabilityVersion(SESSION);
            assertThat(result.seatIds()).containsExactly(SEAT_1, SEAT_2, SEAT_3);
        }

        @Test
        @DisplayName("Hồi quy: 2 ghế đích danh + 1 vé theo khu — so số hàng database với số ghế ĐÍCH DANH")
        void so_voi_so_ghe_dich_danh() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1, SEAT_2), USER)).thenReturn(2);
            when(seats.allocateSeatedInZone(SESSION, "A", 1, USER)).thenReturn(List.of(SEAT_3));

            var result = handler.handle(command(List.of(SEAT_1, SEAT_2), List.of(zone("A", 1)), List.of()));

            assertThat(result.seatIds()).containsExactly(SEAT_1, SEAT_2, SEAT_3);
        }

        @Test
        @DisplayName("Ghế gửi trùng ba lần chỉ xin cổng và database một lần")
        void ghe_trung_lap_xin_mot_lan() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1), USER)).thenReturn(1);

            var result = handler.handle(command(List.of(SEAT_1, SEAT_1, SEAT_1), List.of(), List.of()));

            verify(gate).tryAcquire(eq(SESSION), eq(List.of(SEAT_1)), any(), eq(USER), anyInt());
            assertThat(result.seatIds()).containsExactly(SEAT_1);
        }

        @Test
        @DisplayName("AvailabilityChanged chỉ bắn SAU commit, và commit không trả khoá Redis")
        void ban_su_kien_sau_commit() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1), USER)).thenReturn(1);

            handler.handle(command(List.of(SEAT_1), List.of(), List.of()));

            verifyNoInteractions(publisher);
            commit();
            verify(publisher).availabilityChanged(SESSION, VERSION);
            verify(gate, never()).release(any(), anyList());
        }
    }

    @Nested
    @DisplayName("Từ chối trước khi chạm khoá")
    class TuChoiSom {

        @Test
        @DisplayName("Suất chưa dựng tồn kho: SESSION_NOT_FOUND, không khoá gì")
        void suat_khong_ton_tai() {
            when(sessions.findBySessionId(SESSION)).thenReturn(Optional.empty());

            assertRejected(
                    () -> handler.handle(command(List.of(SEAT_1), List.of(), List.of())),
                    InventoryErrorCode.SESSION_NOT_FOUND);
            verifyNoInteractions(seats, gate, holds);
        }

        @Test
        @DisplayName("Chưa tới giờ mở bán: SALES_CLOSED kèm giờ mở và giờ đóng")
        void chua_mo_ban() {
            Instant opens = NOW.plusSeconds(1);
            Instant closes = NOW.plus(Duration.ofDays(1));
            givenSession(opens, closes);

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1), List.of(), List.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.SALES_CLOSED);
                        assertThat(e.meta()).containsEntry("opensAt", opens).containsEntry("closesAt", closes);
                    });
            verifyNoInteractions(seats, gate, holds);
        }

        @Test
        @DisplayName("Đúng giờ đóng bán: đã đóng")
        void dung_gio_dong_ban() {
            givenSession(NOW.minus(Duration.ofDays(1)), NOW);

            assertRejected(
                    () -> handler.handle(command(List.of(SEAT_1), List.of(), List.of())),
                    InventoryErrorCode.SALES_CLOSED);
            verifyNoInteractions(seats, gate, holds);
        }

        @Test
        @DisplayName("Giữ chỗ rỗng: HOLD_LIMIT_EXCEEDED (400), không khoá gì")
        void giu_cho_rong() {
            assertThatThrownBy(() -> handler.handle(command(List.of(), List.of(), List.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.HOLD_LIMIT_EXCEEDED);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(400);
                        assertThat(e.getMessage()).contains("at least one");
                    });
            verifyNoInteractions(seats, gate, holds);
        }

        @Test
        @DisplayName("Vượt trần vé ngồi mỗi lần giữ: từ chối kèm cả ba trần để giao diện hiện đúng")
        void vuot_tran_ve_ngoi() {
            assertThatThrownBy(() ->
                            handler.handle(command(List.of(SEAT_1, SEAT_2, SEAT_3), List.of(zone("A", 2)), List.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.HOLD_LIMIT_EXCEEDED);
                        assertThat(e.getMessage()).contains("seated");
                        assertThat(e.meta())
                                .containsEntry("maxSeatedPerHold", 4)
                                .containsEntry("maxStandingPerHold", 6)
                                .containsEntry("maxUnitsPerHold", 8);
                    });
            verifyNoInteractions(seats, gate, holds);
        }

        @Test
        @DisplayName("Vượt trần vé đứng mỗi lần giữ: từ chối trước khi cấp phát")
        void vuot_tran_ve_dung() {
            assertThatThrownBy(() -> handler.handle(
                            command(List.of(), List.of(), List.of(standing("GA", 4), standing("GB", 3)))))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.getMessage()).contains("standing"));
            verifyNoInteractions(seats, gate, holds);
        }

        @Test
        @DisplayName("Riêng từng loại đạt nhưng tổng vượt: Too many tickets")
        void tong_vuot_tran() {
            assertThatThrownBy(
                            () -> handler.handle(command(List.of(), List.of(zone("A", 4)), List.of(standing("GA", 5)))))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.HOLD_LIMIT_EXCEEDED);
                        assertThat(e.getMessage()).isEqualTo("Too many tickets in one hold");
                    });
        }

        @Test
        @DisplayName("Dòng theo khu số lượng 0: dừng ở bước dựng domain, không chạm cổng nào")
        void dong_so_luong_khong() {
            assertThatThrownBy(() -> handler.handle(command(List.of(), List.of(), List.of(standing("GA", 0)))))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(sessions, seats, gate, holds);
        }
    }

    @Nested
    @DisplayName("Trần cộng dồn mỗi tài khoản")
    class TranCongDon {

        @Test
        @DisplayName("Đã có 8, xin thêm 3 trên trần 10: CUSTOMER_LIMIT_EXCEEDED kèm limit/used/remaining")
        void vuot_tran_cong_don() {
            when(seats.countUnitsHeldBy(SESSION, USER)).thenReturn(8);

            assertThatThrownBy(() -> handler.handle(command(List.of(), List.of(), List.of(standing("GA", 3)))))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.CUSTOMER_LIMIT_EXCEEDED);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(409);
                        assertThat(e.meta())
                                .containsEntry("limit", 10)
                                .containsEntry("used", 8)
                                .containsEntry("remaining", 2);
                    });
            verify(seats, never()).allocateStanding(any(), anyString(), anyInt(), any());
            verifyNoInteractions(gate, holds);
        }

        @Test
        @DisplayName("Khoá (người dùng, suất) được lấy TRƯỚC khi đếm — nếu không, hai tab cùng đếm ra số cũ")
        void khoa_truoc_khi_dem() {
            when(seats.allocateStanding(SESSION, "GA", 1, USER)).thenReturn(List.of(SEAT_1));

            handler.handle(command(List.of(), List.of(), List.of(standing("GA", 1))));

            InOrder order = inOrder(seats);
            order.verify(seats).lockCustomerSession(USER, SESSION);
            order.verify(seats).countUnitsHeldBy(SESSION, USER);
        }

        @Test
        @DisplayName("Đã có 9, xin đúng 1 chỗ còn lại: được — biên trần cộng dồn là bao gồm")
        void dung_bang_tran_cong_don() {
            when(seats.countUnitsHeldBy(SESSION, USER)).thenReturn(9);
            when(seats.holdSeated(SESSION, List.of(SEAT_1), USER)).thenReturn(1);

            var result = handler.handle(command(List.of(SEAT_1, SEAT_1), List.of(), List.of()));

            assertThat(result.seatIds()).containsExactly(SEAT_1);
        }
    }

    @Nested
    @DisplayName("Cổng Redis")
    class CongRedis {

        @Test
        @DisplayName("Cổng báo ghế đã bị chiếm: SEAT_UNAVAILABLE kèm đúng ghế mất, không ghi database")
        void cong_bao_ghe_bi_chiem() {
            when(gate.tryAcquire(eq(SESSION), anyList(), any(), eq(USER), anyInt()))
                    .thenReturn(List.of(SEAT_2));

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1, SEAT_2), List.of(), List.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.SEAT_UNAVAILABLE);
                        assertThat(e.meta()).containsEntry("unavailableSeatIds", List.of(SEAT_2));
                    });
            verify(seats, never()).holdSeated(any(), anyList(), any());
            verify(holds, never()).save(any());
        }

        @Test
        @DisplayName("Cổng từ chối thì rollback KHÔNG xoá khoá — khoá đó là của người khác")
        void cong_tu_choi_khong_xoa_khoa_nguoi_khac() {
            when(gate.tryAcquire(eq(SESSION), anyList(), any(), eq(USER), anyInt()))
                    .thenReturn(List.of(SEAT_1));

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1), List.of(), List.of())))
                    .isInstanceOf(ApiException.class);
            rollback();

            verify(gate, never()).release(any(), anyList());
        }

        @Test
        @DisplayName("Redis chết: INVENTORY_UNAVAILABLE (503), không fallback xuống database")
        void redis_chet_khong_fallback() {
            when(gate.tryAcquire(eq(SESSION), anyList(), any(), eq(USER), anyInt()))
                    .thenThrow(new AvailabilityGate.GateUnavailableException("down", new RuntimeException()));

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1), List.of(), List.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.INVENTORY_UNAVAILABLE);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(503);
                    });
            verify(seats, never()).holdSeated(any(), anyList(), any());
            verify(holds, never()).save(any());
            verify(sessions, never()).bumpAvailabilityVersion(any());
        }

        @Test
        @DisplayName("Redis chết nhưng chỉ mua vé đứng: vẫn bán được vì vé đứng không qua cổng")
        void redis_chet_ve_dung_van_ban() {
            when(gate.tryAcquire(any(), anyList(), any(), any(), anyInt()))
                    .thenThrow(new AvailabilityGate.GateUnavailableException("down", new RuntimeException()));
            when(seats.allocateStanding(SESSION, "GA", 2, USER)).thenReturn(List.of(SEAT_1, SEAT_2));

            var result = handler.handle(command(List.of(), List.of(), List.of(standing("GA", 2))));

            assertThat(result.seatIds()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Database là chốt chặn, và khoá Redis được trả khi rollback")
    class ChotChanDatabase {

        @Test
        @DisplayName("Redis cho qua nhưng database giữ được ít hơn: SEAT_UNAVAILABLE, rollback trả khoá")
        void database_tu_choi_sau_cong() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1, SEAT_2), USER)).thenReturn(1);

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1, SEAT_2), List.of(), List.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(InventoryErrorCode.SEAT_UNAVAILABLE));
            verify(holds, never()).save(any());

            rollback();
            verify(gate).release(SESSION, List.of(SEAT_1, SEAT_2));
        }

        @Test
        @DisplayName("Ghế đích danh giữ được nhưng zone vé đứng hết: ZONE_SOLD_OUT, khoá Redis trả lại")
        void ve_dung_het_thi_tra_khoa_ghe() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1), USER)).thenReturn(1);
            when(seats.allocateStanding(SESSION, "GA", 3, USER)).thenReturn(List.of(SEAT_2));

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1), List.of(), List.of(standing("GA", 3)))))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.ZONE_SOLD_OUT);
                        assertThat(e.meta())
                                .containsEntry("zoneCode", "GA")
                                .containsEntry("requested", 3)
                                .containsEntry("available", 1);
                    });
            verify(holds, never()).save(any());

            rollback();
            verify(gate).release(SESSION, List.of(SEAT_1));
            verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("Khu vé ngồi không đủ: ZONE_SOLD_OUT, và vé đứng phía sau không bị cấp phát công cốc")
        void khu_ngoi_khong_du() {
            when(seats.allocateSeatedInZone(SESSION, "A", 3, USER)).thenReturn(List.of(SEAT_1, SEAT_2));

            assertThatThrownBy(
                            () -> handler.handle(command(List.of(), List.of(zone("A", 3)), List.of(standing("GA", 1)))))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.ZONE_SOLD_OUT);
                        assertThat(e.meta()).containsEntry("requested", 3).containsEntry("available", 2);
                    });
            verify(seats, never()).allocateStanding(any(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("Unique index nổ lúc lưu giữ chỗ: lỗi lan ra để rollback, và khoá Redis được trả")
        void unique_index_no() {
            when(seats.holdSeated(SESSION, List.of(SEAT_1), USER)).thenReturn(1);
            doThrow(new SeatHoldRepository.SeatAlreadyHeldException(new RuntimeException("uq")))
                    .when(holds)
                    .save(any());

            assertThatThrownBy(() -> handler.handle(command(List.of(SEAT_1), List.of(), List.of())))
                    .isInstanceOf(SeatHoldRepository.SeatAlreadyHeldException.class);
            verify(sessions, never()).bumpAvailabilityVersion(any());

            rollback();
            verify(gate).release(SESSION, List.of(SEAT_1));
            verifyNoInteractions(publisher);
        }
    }

    // ------------------------------------------------------------------------------------------

    private void givenSession(Instant opens, Instant closes) {
        when(sessions.findBySessionId(SESSION))
                .thenReturn(Optional.of(new SessionInventory(
                        UUID.randomUUID(),
                        SESSION,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        opens,
                        closes,
                        new PurchaseLimits(4, 6, 8, 10),
                        Duration.ofMinutes(10))));
    }

    private SeatHold savedHold() {
        ArgumentCaptor<SeatHold> captor = ArgumentCaptor.forClass(SeatHold.class);
        verify(holds).save(captor.capture());
        return captor.getValue();
    }

    private static void assertRejected(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, InventoryErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(code));
    }

    private static PlaceHoldHandler.Command command(
            List<UUID> seatIds,
            List<PlaceHoldHandler.Command.ZoneLine> zones,
            List<PlaceHoldHandler.Command.StandingLine> standing) {
        return new PlaceHoldHandler.Command(SESSION, USER, seatIds, zones, standing);
    }

    private static PlaceHoldHandler.Command.ZoneLine zone(String code, int quantity) {
        return new PlaceHoldHandler.Command.ZoneLine(code, quantity);
    }

    private static PlaceHoldHandler.Command.StandingLine standing(String code, int quantity) {
        return new PlaceHoldHandler.Command.StandingLine(code, quantity);
    }

    private static void commit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private static void rollback() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }
}
