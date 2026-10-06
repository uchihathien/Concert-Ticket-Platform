// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Khách tự bỏ giữ chỗ — một trong bốn đường nhả chỗ phải trả lại hạn mức (ADR-1014). */
@Service
public class ReleaseHoldHandler {

    private final SeatHoldRepository holds;
    private final SeatRepository seats;
    private final SessionInventoryRepository sessions;
    private final AvailabilityGate gate;
    private final AvailabilityPublisher publisher;
    private final Clock clock;

    public ReleaseHoldHandler(
            SeatHoldRepository holds,
            SeatRepository seats,
            SessionInventoryRepository sessions,
            AvailabilityGate gate,
            AvailabilityPublisher publisher,
            Clock clock) {
        this.holds = holds;
        this.seats = seats;
        this.sessions = sessions;
        this.gate = gate;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void handle(UUID holdId, UUID userId) {
        SeatHold hold = holds.findById(holdId).orElseThrow(ReleaseHoldHandler::notFound);

        // Giữ chỗ của người khác cũng trả 404: trả 403 là xác nhận holdId đó có thật.
        if (!hold.isOwnedBy(userId)) {
            throw notFound();
        }
        if (hold.status() != HoldStatus.ACTIVE) {
            return; // Idempotent: bỏ hai lần vẫn chỉ là bỏ.
        }

        hold.release();
        holds.updateStatus(hold.id(), hold.status(), clock.instant());
        seats.release(hold.seatIds());

        UUID sessionId = hold.eventSessionId();
        long version = sessions.bumpAvailabilityVersion(sessionId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gate.release(sessionId, hold.seatIds());
                publisher.availabilityChanged(sessionId, version);
            }
        });
    }

    private static ApiException notFound() {
        return new ApiException(InventoryErrorCode.HOLD_NOT_FOUND, "Hold not found");
    }
}
