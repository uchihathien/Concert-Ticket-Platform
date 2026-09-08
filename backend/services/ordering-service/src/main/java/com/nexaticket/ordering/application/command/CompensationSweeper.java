// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.port.CheckoutSagaRepository;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bù trừ lại các saga đã hỏng cả ở bước bù trừ — lưới an toàn thứ hai (sagas.md §2).
 *
 * <p>Chạy mỗi 30 giây. Không có nó, một lần inventory-service không phản hồi đúng lúc rollback sẽ
 * để ghế kẹt ở {@code RESERVED} cho tới khi {@code payment_expires_at} hết hạn 15 phút sau — 15
 * phút không bán được ghế đó trong một đợt mở bán là mất doanh thu thật.
 *
 * <p>Không có {@code @Transactional} bao ngoài, cùng lý do với {@link PlaceOrderHandler}: các lời
 * gọi mạng ở đây không được giữ kết nối database.
 */
@Service
public class CompensationSweeper {

    private static final Logger log = LoggerFactory.getLogger(CompensationSweeper.class);
    private static final int BATCH_SIZE = 50;

    /** Chờ một nhịp trước khi thử lại: bù trừ vừa hỏng thì bên kia nhiều khả năng vẫn đang hỏng. */
    private static final Duration BACKOFF = Duration.ofSeconds(30);

    private final CheckoutSagaRepository sagas;
    private final InventoryPort inventory;
    private final PaymentPort payments;
    private final OrderTransactions tx;
    private final Clock clock;

    public CompensationSweeper(
            CheckoutSagaRepository sagas,
            InventoryPort inventory,
            PaymentPort payments,
            OrderTransactions tx,
            Clock clock) {
        this.sagas = sagas;
        this.inventory = inventory;
        this.payments = payments;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @return số saga đã bù trừ xong ở lượt này
     */
    public int runOnce() {
        List<CheckoutSaga> pending =
                sagas.claimCompensationPending(clock.instant().minus(BACKOFF), BATCH_SIZE);
        int compensated = 0;
        for (CheckoutSaga saga : pending) {
            try {
                if (saga.isPaymentIntentOpen()) {
                    payments.cancelIntent(saga.orderId());
                }
                if (saga.isSeatsReserved()) {
                    inventory.cancelReservation(saga.orderId());
                }
                saga.compensated();
                compensated++;
            } catch (RuntimeException e) {
                log.warn("Bù trừ lại saga {} vẫn hỏng (lần {})", saga.orderId(), saga.attempts() + 1, e);
                saga.compensationFailed(e.toString());
            }
            tx.updateSaga(saga);
        }
        return compensated;
    }
}
