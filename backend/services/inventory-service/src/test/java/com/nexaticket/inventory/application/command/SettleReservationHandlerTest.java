// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.inventory.domain.model.HoldStatus;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import java.time.Clock;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** RESERVED → SOLD và bù trừ saga: cả hai idempotent, "không có gì để làm" không phải lỗi. */
class SettleReservationHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID HOLD = UUID.fromString("00000000-0000-4000-a000-0000000000c1");
    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-a000-0000000000d1");
    private static final List<UUID> SEATS = List.of(UUID.randomUUID(), UUID.randomUUID());

    private final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SessionInventoryRepository sessions = mock(SessionInventoryRepository.class);
    private final AvailabilityPublisher publisher = mock(AvailabilityPublisher.class);

    private final SettleReservationHandler handler =
            new SettleReservationHandler(holds, seats, sessions, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(sessions.bumpAvailabilityVersion(SESSION)).thenReturn(99L);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("Chốt bán")
    class ChotBan {

        @Test
        @DisplayName("Đặt chỗ đang RESERVED: chuyển SETTLED, chỗ sang SOLD, không bump version")
        void chot_ban_lan_dau() {
            givenReservation();
            when(holds.updateReservationStatus(ORDER, "SETTLED", NOW)).thenReturn(1);

            assertThat(handler.settle(ORDER)).isTrue();

            verify(seats).markSold(SEATS);
            verify(sessions, never()).bumpAvailabilityVersion(any());
            verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("Webhook đến lần hai: đặt chỗ đã SETTLED nên trả false và không bán lại")
        void chot_ban_lan_hai() {
            givenReservation();
            when(holds.updateReservationStatus(ORDER, "SETTLED", NOW)).thenReturn(0);

            assertThat(handler.settle(ORDER)).isFalse();

            verify(seats, never()).markSold(anyList());
        }

        @Test
        @DisplayName("Đơn không có đặt chỗ ở Inventory: false, không ném, không ghi gì")
        void khong_co_dat_cho() {
            when(holds.findHoldIdByOrder(ORDER)).thenReturn(Optional.empty());

            assertThat(handler.settle(ORDER)).isFalse();

            verify(holds, never()).updateReservationStatus(any(), anyString(), any());
            verifyNoInteractions(seats);
        }

        @Test
        @DisplayName("Có bản ghi đặt chỗ nhưng giữ chỗ trỏ tới đã mất: false thay vì NullPointerException")
        void giu_cho_mat() {
            when(holds.findHoldIdByOrder(ORDER)).thenReturn(Optional.of(HOLD));
            when(holds.findById(HOLD)).thenReturn(Optional.empty());

            assertThat(handler.settle(ORDER)).isFalse();
            assertThat(handler.cancel(ORDER)).isFalse();
            verifyNoInteractions(seats);
        }
    }

    @Nested
    @DisplayName("Bù trừ")
    class BuTru {

        @Test
        @DisplayName("Huỷ đặt chỗ RESERVED: chỗ về AVAILABLE, bump version, bắn sự kiện sau commit")
        void huy_lan_dau() {
            givenReservation();
            when(holds.updateReservationStatus(ORDER, "CANCELLED", NOW)).thenReturn(1);

            assertThat(handler.cancel(ORDER)).isTrue();

            verify(seats).release(SEATS);
            verify(sessions).bumpAvailabilityVersion(SESSION);
            verifyNoInteractions(publisher);
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(publisher).availabilityChanged(SESSION, 99L);
        }

        @Test
        @DisplayName("Huỷ lần hai, hoặc huỷ sau khi đã bán: false, chỗ không bị nhả")
        void huy_lan_hai_hoac_sau_khi_ban() {
            givenReservation();
            when(holds.updateReservationStatus(ORDER, "CANCELLED", NOW)).thenReturn(0);

            assertThat(handler.cancel(ORDER)).isFalse();

            verify(seats, never()).release(anyList());
            verify(sessions, never()).bumpAvailabilityVersion(any());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        @Test
        @DisplayName("Saga bù trừ một việc chưa từng xảy ra: false, không ném")
        void bu_tru_viec_chua_xay_ra() {
            when(holds.findHoldIdByOrder(ORDER)).thenReturn(Optional.empty());

            assertThat(handler.cancel(ORDER)).isFalse();

            verify(holds, never()).updateReservationStatus(eq(ORDER), anyString(), any());
            verifyNoInteractions(seats, publisher);
        }
    }

    private void givenReservation() {
        when(holds.findHoldIdByOrder(ORDER)).thenReturn(Optional.of(HOLD));
        when(holds.findById(HOLD))
                .thenReturn(Optional.of(SeatHold.rehydrate(
                        HOLD, SESSION, UUID.randomUUID(), NOW.minusSeconds(60), SEATS, HoldStatus.CONVERTED)));
    }
}
