// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.SagaStatus;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import com.nexaticket.ordering.support.RecordingSagaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Lưới an toàn thứ hai của saga: bù trừ lại những saga mà bù trừ đồng bộ đã hỏng. */
class CompensationSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private RecordingSagaRepository sagas;
    private InventoryPort inventory;
    private PaymentPort payments;
    private CompensationSweeper sweeper;

    @BeforeEach
    void setUp() {
        sagas = new RecordingSagaRepository();
        inventory = mock(InventoryPort.class);
        payments = mock(PaymentPort.class);
        OrderTransactions tx = new OrderTransactions(mock(OrderRepository.class), sagas, mock(OutboxPort.class));
        sweeper = new CompensationSweeper(sagas, inventory, payments, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CheckoutSaga pending(boolean seats, boolean intent, int attempts) {
        CheckoutSaga saga = CheckoutSaga.rehydrate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SagaStatus.COMPENSATION_PENDING,
                seats,
                intent,
                attempts);
        sagas.pending.add(saga);
        return saga;
    }

    @Test
    @DisplayName("Chỉ nhận saga đã nằm yên ít nhất 30 giây, mỗi lượt tối đa 50")
    void cho_mot_nhip_va_gioi_han_lo() {
        sweeper.runOnce();

        assertThat(sagas.lastNotUpdatedSince).isEqualTo(NOW.minusSeconds(30));
        assertThat(sagas.lastBatchSize).isEqualTo(50);
    }

    @Test
    @DisplayName("Bù trừ đủ hai bước, theo thứ tự đóng intent rồi nhả chỗ; saga sang COMPENSATED")
    void bu_tru_du_hai_buoc() {
        CheckoutSaga saga = pending(true, true, 2);

        assertThat(sweeper.runOnce()).isEqualTo(1);

        InOrder sequence = inOrder(payments, inventory);
        sequence.verify(payments).cancelIntent(saga.orderId());
        sequence.verify(inventory).cancelReservation(saga.orderId());
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("Chỉ bù trừ bước đã thực sự làm: chưa mở intent thì không gọi payment")
    void chi_bu_tru_buoc_da_lam() {
        CheckoutSaga saga = pending(true, false, 0);

        sweeper.runOnce();

        verifyNoInteractions(payments);
        verify(inventory).cancelReservation(saga.orderId());
    }

    @Test
    @DisplayName("Vẫn hỏng: giữ COMPENSATION_PENDING, tăng số lần thử, ghi lại lỗi, không đếm là xong")
    void van_hong_thi_tang_lan_thu() {
        CheckoutSaga saga = pending(true, true, 4);
        doThrow(new RemoteCallException("inventory", "van chet", null))
                .when(inventory)
                .cancelReservation(saga.orderId());

        assertThat(sweeper.runOnce()).isZero();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATION_PENDING);
        assertThat(saga.attempts()).isEqualTo(5);
        assertThat(saga.lastError()).contains("van chet");
        assertThat(sagas.last().attempts()).isEqualTo(5);
        assertThat(saga.isSeatsReserved()).isTrue();
    }

    @Test
    @DisplayName("Một saga hỏng không chặn saga sau trong cùng lô")
    void mot_saga_hong_khong_chan_lo() {
        CheckoutSaga broken = pending(false, true, 0);
        CheckoutSaga healthy = pending(true, false, 0);
        doThrow(new RemoteCallException("payment", "down", null)).when(payments).cancelIntent(broken.orderId());

        assertThat(sweeper.runOnce()).isEqualTo(1);

        assertThat(healthy.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(broken.status()).isEqualTo(SagaStatus.COMPENSATION_PENDING);
        verify(inventory, never()).cancelReservation(broken.orderId());
        assertThat(sagas.writes).hasSize(2);
    }
}
