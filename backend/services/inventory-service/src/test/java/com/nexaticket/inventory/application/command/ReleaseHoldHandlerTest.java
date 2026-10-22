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
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Khách tự bỏ giữ chỗ: chỉ chủ sở hữu, idempotent, và khoá Redis trả sau commit. */
class ReleaseHoldHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-a000-0000000000b2");
    private static final UUID HOLD = UUID.fromString("00000000-0000-4000-a000-0000000000c1");
    private static final List<UUID> SEATS = List.of(UUID.randomUUID(), UUID.randomUUID());

    private final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SessionInventoryRepository sessions = mock(SessionInventoryRepository.class);
    private final AvailabilityGate gate = mock(AvailabilityGate.class);
    private final AvailabilityPublisher publisher = mock(AvailabilityPublisher.class);

    private final ReleaseHoldHandler handler =
            new ReleaseHoldHandler(holds, seats, sessions, gate, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(sessions.bumpAvailabilityVersion(SESSION)).thenReturn(7L);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("Đường thành công")
    class DuongThanhCong {

        @Test
        @DisplayName("Chủ sở hữu bỏ giữ chỗ ACTIVE: ghi RELEASED, nhả chỗ, bump version")
        void chu_so_huu_bo_giu_cho() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(300));

            handler.handle(HOLD, OWNER);

            InOrder order = inOrder(holds, seats, sessions);
            order.verify(holds).updateStatus(HOLD, HoldStatus.RELEASED, NOW);
            order.verify(seats).release(SEATS);
            order.verify(sessions).bumpAvailabilityVersion(SESSION);
        }

        @Test
        @DisplayName("Khoá Redis và AvailabilityChanged chỉ xảy ra sau commit")
        void sau_commit_moi_tra_khoa() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(300));

            handler.handle(HOLD, OWNER);
            verifyNoInteractions(gate, publisher);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(gate).release(SESSION, SEATS);
            verify(publisher).availabilityChanged(SESSION, 7L);
        }

        @Test
        @DisplayName("Rollback thì không trả khoá Redis — database vẫn coi chỗ là đang giữ")
        void rollback_khong_tra_khoa() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(300));

            handler.handle(HOLD, OWNER);
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            verifyNoInteractions(gate, publisher);
        }

        @Test
        @DisplayName("Giữ chỗ ACTIVE đã quá hạn nhưng worker chưa quét: khách vẫn tự bỏ được")
        void qua_han_chua_quet_van_bo_duoc() {
            givenHold(HoldStatus.ACTIVE, NOW.minusSeconds(1));

            handler.handle(HOLD, OWNER);

            verify(seats).release(SEATS);
        }
    }

    @Nested
    @DisplayName("Phân quyền")
    class PhanQuyen {

        @Test
        @DisplayName("Giữ chỗ của người khác: HOLD_NOT_FOUND chứ không phải 403 — không xác nhận holdId có thật")
        void nguoi_khac_nhan_404() {
            givenHold(HoldStatus.ACTIVE, NOW.plusSeconds(300));

            assertThatThrownBy(() -> handler.handle(HOLD, STRANGER)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.HOLD_NOT_FOUND);
                assertThat(e.errorCode().httpStatus()).isEqualTo(404);
            });
            verify(holds, never()).updateStatus(any(), any(), any());
            verifyNoInteractions(seats, sessions, gate, publisher);
        }

        @Test
        @DisplayName("Không có giữ chỗ: HOLD_NOT_FOUND, cùng thông điệp với trường hợp của người khác")
        void khong_ton_tai() {
            when(holds.findById(HOLD)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.handle(HOLD, OWNER)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.HOLD_NOT_FOUND);
                assertThat(e.getMessage()).isEqualTo("Hold not found");
            });
        }
    }

    @Nested
    @DisplayName("Idempotent")
    class Idempotent {

        @ParameterizedTest
        @EnumSource(value = HoldStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Giữ chỗ không còn ACTIVE: không làm gì, không ném — kể cả đã thành đơn")
        void khong_active_thi_bo_qua(HoldStatus status) {
            givenHold(status, NOW.plusSeconds(300));

            handler.handle(HOLD, OWNER);

            verify(holds, never()).updateStatus(any(), any(), any());
            verify(seats, never()).release(anyList());
            verifyNoInteractions(sessions, gate, publisher);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }
    }

    private void givenHold(HoldStatus status, Instant expiresAt) {
        when(holds.findById(HOLD))
                .thenReturn(Optional.of(SeatHold.rehydrate(HOLD, SESSION, OWNER, expiresAt, SEATS, status)));
    }
}
