// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RESERVED → SOLD sau khi thanh toán xác nhận, và huỷ đặt chỗ khi saga hỏng.
 *
 * <p>Cả hai đường đều idempotent: webhook thanh toán có thể đến hai lần (SePay retry), và bước bù
 * trừ của saga có thể chạy lại. Tính idempotent nằm ở {@code updateReservationStatus} — nó trả 0
 * khi bản ghi đã ở trạng thái đích, nên lần gọi thứ hai không làm gì thêm.
 */
@Service
public class SettleReservationHandler {

    private final SeatHoldRepository holds;
    private final SeatRepository seats;
    private final SessionInventoryRepository sessions;
    private final AvailabilityPublisher publisher;
    private final Clock clock;

    public SettleReservationHandler(
            SeatHoldRepository holds,
            SeatRepository seats,
            SessionInventoryRepository sessions,
            AvailabilityPublisher publisher,
            Clock clock) {
        this.holds = holds;
        this.seats = seats;
        this.sessions = sessions;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void settle(UUID orderId) {
        SeatHold hold = holdOf(orderId);
        if (holds.updateReservationStatus(orderId, "SETTLED", clock.instant()) == 0) {
            return;
        }
        seats.markSold(hold.seatIds());
        // Không bump version: RESERVED và SOLD đều là "không mua được", client không thấy khác gì.
    }

    /** Bù trừ khi saga checkout hỏng: chỗ về AVAILABLE, hạn mức của khách được trả lại. */
    @Transactional
    public void cancel(UUID orderId) {
        SeatHold hold = holdOf(orderId);
        if (holds.updateReservationStatus(orderId, "CANCELLED", clock.instant()) == 0) {
            return;
        }
        seats.release(hold.seatIds());

        UUID sessionId = hold.eventSessionId();
        long version = sessions.bumpAvailabilityVersion(sessionId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.availabilityChanged(sessionId, version);
            }
        });
    }

    private SeatHold holdOf(UUID orderId) {
        UUID holdId = holds.findHoldIdByOrder(orderId)
                .orElseThrow(
                        () -> new ApiException(InventoryErrorCode.HOLD_NOT_FOUND, "No reservation for this order"));
        return holds.findById(holdId).orElseThrow();
    }
}
