// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.port;

import com.nexaticket.inventory.domain.model.SeatHold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lưu và tra giữ chỗ. */
public interface SeatHoldRepository {

    /**
     * Ghi giữ chỗ và các dòng {@code seat_hold_items}.
     *
     * @throws SeatAlreadyHeldException khi vi phạm {@code uq_hold_item_active} — chốt chặn oversell
     */
    void save(SeatHold hold);

    Optional<SeatHold> findById(UUID holdId);

    void updateStatus(UUID holdId, com.nexaticket.inventory.domain.model.HoldStatus status, Instant at);

    /**
     * Lấy các giữ chỗ ACTIVE đã quá hạn để worker dọn.
     *
     * <p>Dùng {@code FOR UPDATE SKIP LOCKED} để nhiều instance chạy song song mà không giẫm chân.
     */
    List<SeatHold> claimExpired(Instant now, int batchSize);

    void createReservation(UUID orderId, UUID holdId, UUID eventSessionId);

    Optional<UUID> findHoldIdByOrder(UUID orderId);

    int updateReservationStatus(UUID orderId, String status, Instant at);

    /** Vi phạm chốt chặn oversell ở database — nghĩa là cổng Redis đã để lọt. */
    class SeatAlreadyHeldException extends RuntimeException {
        public SeatAlreadyHeldException(Throwable cause) {
            super("Chỗ đã có người giữ (uq_hold_item_active)", cause);
        }
    }
}
