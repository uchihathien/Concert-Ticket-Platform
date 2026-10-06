// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RESERVED → SOLD sau khi thanh toán xác nhận, và huỷ đặt chỗ khi saga hỏng.
 *
 * <p>Cả hai đường đều idempotent: webhook thanh toán có thể đến hai lần (payOS retry cho tới khi
 * nhận 2xx), và bước bù trừ của saga có thể chạy lại. Tính idempotent nằm ở
 * {@code updateReservationStatus} — nó trả 0 khi bản ghi đã ở trạng thái đích, nên lần gọi thứ hai
 * không làm gì thêm.
 *
 * <p><b>Không có đặt chỗ nào cũng là thành công</b>, không phải 404. Hai lý do, và cả hai đều đã
 * cắn thật:
 *
 * <ul>
 *   <li>Saga ghi cờ {@code seatsReserved} TRƯỚC khi gọi Inventory, nên nó có thể bù trừ một việc
 *       chưa từng xảy ra. Ném lỗi ở đây đẩy saga vào {@code COMPENSATION_PENDING} vĩnh viễn vì một
 *       việc vốn không cần làm.
 *   <li>Người gọi giờ là một consumer message nằm trong transaction của chính nó. Một
 *       {@code ApiException} bay qua ranh giới {@code @Transactional} lồng nhau sẽ đánh dấu
 *       transaction là rollback-only; consumer bắt lỗi rồi trả về bình thường vẫn nhận
 *       {@code UnexpectedRollbackException} lúc commit, message bị giao lại, và vòng lặp đó không
 *       bao giờ dừng.
 * </ul>
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

    /** @return true nếu lần gọi này thực sự chốt chỗ; false nếu đã chốt rồi hoặc không có đặt chỗ */
    @Transactional
    public boolean settle(UUID orderId) {
        SeatHold hold = holdOf(orderId).orElse(null);
        if (hold == null || holds.updateReservationStatus(orderId, "SETTLED", clock.instant()) == 0) {
            return false;
        }
        seats.markSold(hold.seatIds());
        // Không bump version: RESERVED và SOLD đều là "không mua được", client không thấy khác gì.
        return true;
    }

    /**
     * Bù trừ khi saga checkout hỏng, hoặc khi đơn đóng: chỗ về AVAILABLE, hạn mức của khách được
     * trả lại.
     *
     * @return true nếu lần gọi này thực sự nhả chỗ; false nếu đã nhả rồi hoặc không có đặt chỗ nào
     */
    @Transactional
    public boolean cancel(UUID orderId) {
        SeatHold hold = holdOf(orderId).orElse(null);
        if (hold == null || holds.updateReservationStatus(orderId, "CANCELLED", clock.instant()) == 0) {
            return false;
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
        return true;
    }

    private Optional<SeatHold> holdOf(UUID orderId) {
        return holds.findHoldIdByOrder(orderId).flatMap(holds::findById);
    }
}
