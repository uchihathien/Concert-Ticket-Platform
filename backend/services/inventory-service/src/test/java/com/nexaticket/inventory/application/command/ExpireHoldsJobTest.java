// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.inventory.domain.model.HoldStatus;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Worker nhả giữ chỗ hết hạn — đường nhả thứ hai của ADR-1014. */
class ExpireHoldsJobTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final UUID SESSION_A = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID SESSION_B = UUID.fromString("00000000-0000-4000-a000-0000000000a2");

    private final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SessionInventoryRepository sessions = mock(SessionInventoryRepository.class);
    private final AvailabilityGate gate = mock(AvailabilityGate.class);
    private final AvailabilityPublisher publisher = mock(AvailabilityPublisher.class);

    private final ExpireHoldsJob job =
            new ExpireHoldsJob(holds, seats, sessions, gate, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("Không có gì hết hạn: trả 0 và không chạm cổng nào khác")
    void khong_co_gi_het_han() {
        when(holds.claimExpired(NOW, 200)).thenReturn(List.of());

        assertThat(job.runOnce()).isZero();

        verifyNoInteractions(seats, sessions, gate, publisher);
    }

    @Test
    @DisplayName("Mỗi giữ chỗ hết hạn: ghi EXPIRED, nhả chỗ, xoá khoá Redis, bump version của đúng suất")
    void nha_tung_giu_cho() {
        when(holds.updateStatus(any(), any(), any())).thenReturn(true);
        List<UUID> seatsA = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<UUID> seatsB = List.of(UUID.randomUUID());
        SeatHold holdA = expiredActive(SESSION_A, seatsA);
        SeatHold holdB = expiredActive(SESSION_B, seatsB);
        when(holds.claimExpired(NOW, 200)).thenReturn(List.of(holdA, holdB));
        when(sessions.bumpAvailabilityVersion(SESSION_A)).thenReturn(11L);
        when(sessions.bumpAvailabilityVersion(SESSION_B)).thenReturn(22L);

        assertThat(job.runOnce()).isEqualTo(2);

        InOrder order = inOrder(holds, seats, gate, publisher);
        order.verify(holds).updateStatus(holdA.id(), HoldStatus.EXPIRED, NOW);
        order.verify(seats).release(seatsA);
        order.verify(gate).release(SESSION_A, seatsA);
        order.verify(publisher).availabilityChanged(SESSION_A, 11L);
        order.verify(holds).updateStatus(holdB.id(), HoldStatus.EXPIRED, NOW);
        order.verify(seats).release(seatsB);
        order.verify(gate).release(SESSION_B, seatsB);
        order.verify(publisher).availabilityChanged(SESSION_B, 22L);
        assertThat(holdA.status()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    @DisplayName("Quét theo đồng hồ được tiêm và lô 200 — không dùng giờ máy")
    void quet_theo_dong_ho_tiem() {
        when(holds.claimExpired(any(), eq(200))).thenReturn(List.of());

        job.runOnce();

        verify(holds).claimExpired(NOW, 200);
    }

    private static SeatHold expiredActive(UUID session, List<UUID> seatIds) {
        return SeatHold.rehydrate(
                UUID.randomUUID(), session, UUID.randomUUID(), NOW.minusSeconds(1), seatIds, HoldStatus.ACTIVE);
    }
}
