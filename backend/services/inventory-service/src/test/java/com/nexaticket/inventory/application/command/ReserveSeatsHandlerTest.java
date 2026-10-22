// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Giữ chỗ → đặt chỗ cho saga checkout: idempotent theo orderId, giá đi từ tồn kho. */
class ReserveSeatsHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-a000-0000000000e1");
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-a000-0000000000f1");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-a000-0000000000b2");
    private static final UUID HOLD = UUID.fromString("00000000-0000-4000-a000-0000000000c1");
    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-a000-0000000000d1");
    private static final UUID SEAT_1 = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID SEAT_2 = UUID.fromString("00000000-0000-4000-a000-000000000002");
    private static final UUID TYPE = UUID.fromString("00000000-0000-4000-a000-000000000011");

    private final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SessionInventoryRepository sessions = mock(SessionInventoryRepository.class);
    private final AvailabilityGate gate = mock(AvailabilityGate.class);

    private final ReserveSeatsHandler handler =
            new ReserveSeatsHandler(holds, seats, sessions, gate, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(holds.findHoldIdByOrder(ORDER)).thenReturn(Optional.empty());
        when(holds.updateStatus(any(), any(), any())).thenReturn(true);
        when(sessions.findBySessionId(SESSION))
                .thenReturn(Optional.of(new SessionInventory(
                        UUID.randomUUID(),
                        SESSION,
                        EVENT,
                        ORG,
                        NOW.minus(Duration.ofDays(1)),
                        NOW.plus(Duration.ofDays(1)),
                        new PurchaseLimits(8, 10, 10, 10),
                        Duration.ofMinutes(10))));
        when(seats.detailsOf(List.of(SEAT_1, SEAT_2)))
                .thenReturn(List.of(
                        new SeatRepository.ReservedSeatRow(
                                SEAT_1, "A-1", "A", "SEATED", "1", TYPE, "Ve ngoi", 1_500_000L),
                        new SeatRepository.ReservedSeatRow(
                                SEAT_2, "GA-1", "GA", "STANDING", null, TYPE, "Ve dung", 800_000L)));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("Đường thành công")
    class DuongThanhCong {

        @Test
        @DisplayName("Giữ chỗ còn hạn: CONVERTED, chỗ sang RESERVED, ghi đặt chỗ, trả giá và nhãn từ tồn kho")
        void dat_cho_thanh_cong() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(60));
            when(seats.reserve(List.of(SEAT_1, SEAT_2))).thenReturn(2);

            var result = handler.handle(new ReserveSeatsHandler.Command(ORDER, HOLD, USER));

            InOrder order = inOrder(holds, seats);
            order.verify(holds).updateStatus(HOLD, HoldStatus.CONVERTED, NOW);
            order.verify(seats).reserve(List.of(SEAT_1, SEAT_2));
            order.verify(holds).createReservation(ORDER, HOLD, SESSION);
            assertThat(result.orderId()).isEqualTo(ORDER);
            assertThat(result.eventSessionId()).isEqualTo(SESSION);
            assertThat(result.eventId()).isEqualTo(EVENT);
            assertThat(result.organizationId()).isEqualTo(ORG);
            assertThat(result.seats())
                    .containsExactly(
                            new ReserveSeatsHandler.ReservedSeat(
                                    SEAT_1, "A-1", "A", "SEATED", "1", TYPE, "Ve ngoi", 1_500_000L),
                            new ReserveSeatsHandler.ReservedSeat(
                                    SEAT_2, "GA-1", "GA", "STANDING", null, TYPE, "Ve dung", 800_000L));
        }

        @Test
        @DisplayName("Khoá Redis chỉ được xoá sau commit — rollback thì giữ nguyên")
        void tra_khoa_sau_commit() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(60));
            when(seats.reserve(List.of(SEAT_1, SEAT_2))).thenReturn(2);

            handler.handle(new ReserveSeatsHandler.Command(ORDER, HOLD, USER));
            verifyNoInteractions(gate);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(gate).release(SESSION, List.of(SEAT_1, SEAT_2));
        }
    }

    @Nested
    @DisplayName("Idempotent theo orderId")
    class IdempotentTheoOrder {

        @Test
        @DisplayName("Saga gọi lại cùng orderId: trả lại kết quả cũ, không đổi trạng thái gì")
        void goi_lai_tra_ket_qua_cu() {
            when(holds.findHoldIdByOrder(ORDER)).thenReturn(Optional.of(HOLD));
            givenHold(HoldStatus.CONVERTED, NOW.minusSeconds(3600));

            var result = handler.handle(new ReserveSeatsHandler.Command(ORDER, HOLD, USER));

            assertThat(result.seats())
                    .extracting(ReserveSeatsHandler.ReservedSeat::priceVnd)
                    .containsExactly(1_500_000L, 800_000L);
            verify(holds, never()).updateStatus(any(), any(), any());
            verify(seats, never()).reserve(anyList());
            verify(holds, never()).createReservation(any(), any(), any());
            verifyNoInteractions(gate);
        }
    }

    @Nested
    @DisplayName("Từ chối")
    class TuChoi {

        @Test
        @DisplayName("Không có giữ chỗ: HOLD_NOT_FOUND")
        void khong_co_giu_cho() {
            when(holds.findById(HOLD)).thenReturn(Optional.empty());

            assertRejected(InventoryErrorCode.HOLD_NOT_FOUND, USER);
        }

        @Test
        @DisplayName("Giữ chỗ của người khác: HOLD_NOT_OWNED (403) ở đường nội bộ, không đổi trạng thái")
        void giu_cho_nguoi_khac() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(60));

            assertRejected(InventoryErrorCode.HOLD_NOT_OWNED, STRANGER);
            verify(holds, never()).updateStatus(any(), any(), any());
            verifyNoInteractions(seats);
        }

        @Test
        @DisplayName("Đúng thời điểm hết hạn: HOLD_EXPIRED, chỗ không bị đụng tới")
        void dung_han() {
            givenHold(HoldStatus.ACTIVE, NOW);

            assertRejected(InventoryErrorCode.HOLD_EXPIRED, USER);
            verify(holds, never()).updateStatus(any(), any(), any());
            verifyNoInteractions(seats);
        }

        @Test
        @DisplayName("Giữ chỗ đã bị khách bỏ: HOLD_EXPIRED dù còn hạn")
        void da_bi_bo() {
            givenHold(HoldStatus.RELEASED, NOW.plusSeconds(60));

            assertRejected(InventoryErrorCode.HOLD_EXPIRED, USER);
            verifyNoInteractions(seats);
        }

        @Test
        @DisplayName("Hồi quy: khách bỏ chỗ giữa lúc đọc và lúc ghi — HOLD_EXPIRED, không đặt ghế nào")
        void thua_cuoc_dua_voi_bo_cho() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(60));
            when(holds.updateStatus(HOLD, HoldStatus.CONVERTED, NOW)).thenReturn(false);

            assertRejected(InventoryErrorCode.HOLD_EXPIRED, USER);
            verifyNoInteractions(seats);
            verify(holds, never()).createReservation(any(), any(), any());
        }

        @Test
        @DisplayName("Chỗ không còn HELD hết (worker đã nhả một phần): SEAT_UNAVAILABLE, không ghi đặt chỗ")
        void cho_khong_con_held() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(60));
            when(seats.reserve(List.of(SEAT_1, SEAT_2))).thenReturn(1);

            assertRejected(InventoryErrorCode.SEAT_UNAVAILABLE, USER);
            verify(holds, never()).createReservation(any(), any(), any());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        @Test
        @DisplayName("Tồn kho của suất biến mất giữa chừng: SESSION_NOT_FOUND thay vì trả kết quả thiếu tổ chức")
        void ton_kho_bien_mat() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(60));
            when(seats.reserve(List.of(SEAT_1, SEAT_2))).thenReturn(2);
            when(sessions.findBySessionId(SESSION)).thenReturn(Optional.empty());

            assertRejected(InventoryErrorCode.SESSION_NOT_FOUND, USER);
        }
    }

    private void assertRejected(InventoryErrorCode code, UUID caller) {
        assertThatThrownBy(() -> handler.handle(new ReserveSeatsHandler.Command(ORDER, HOLD, caller)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private void givenHold(HoldStatus status, Instant expiresAt) {
        when(holds.findById(HOLD))
                .thenReturn(Optional.of(
                        SeatHold.rehydrate(HOLD, SESSION, USER, expiresAt, List.of(SEAT_1, SEAT_2), status)));
    }
}
