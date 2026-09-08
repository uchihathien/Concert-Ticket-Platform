// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ordering gọi: giữ chỗ → đặt chỗ.
 *
 * <p>Idempotent theo {@code orderId}: saga checkout có thể chạy lại bước này sau timeout mạng, và
 * lần thứ hai phải trả đúng kết quả lần đầu chứ không được nổ.
 */
@Service
public class ReserveSeatsHandler {

    private final SeatHoldRepository holds;
    private final SeatRepository seats;
    private final AvailabilityGate gate;
    private final Clock clock;

    public ReserveSeatsHandler(SeatHoldRepository holds, SeatRepository seats, AvailabilityGate gate, Clock clock) {
        this.holds = holds;
        this.seats = seats;
        this.gate = gate;
        this.clock = clock;
    }

    public record Command(UUID orderId, UUID holdId, UUID userId) {}

    public record Result(UUID orderId, UUID eventSessionId, List<UUID> seatIds) {}

    @Transactional
    public Result handle(Command cmd) {
        Optional<UUID> already = holds.findHoldIdByOrder(cmd.orderId());
        if (already.isPresent()) {
            SeatHold done = holds.findById(already.get()).orElseThrow();
            return new Result(cmd.orderId(), done.eventSessionId(), done.seatIds());
        }

        SeatHold hold = holds.findById(cmd.holdId())
                .orElseThrow(() -> new ApiException(InventoryErrorCode.HOLD_NOT_FOUND, "Hold not found"));

        if (!hold.isOwnedBy(cmd.userId())) {
            throw new ApiException(InventoryErrorCode.HOLD_NOT_OWNED, "Hold belongs to another user");
        }
        if (!hold.isUsableAt(clock.instant())) {
            throw new ApiException(InventoryErrorCode.HOLD_EXPIRED, "Hold is no longer active");
        }

        hold.convert(clock.instant());
        holds.updateStatus(hold.id(), hold.status(), clock.instant());

        int reserved = seats.reserve(hold.seatIds());
        if (reserved != hold.size()) {
            throw new ApiException(InventoryErrorCode.SEAT_UNAVAILABLE, "Seats are no longer in HELD state");
        }
        holds.createReservation(cmd.orderId(), hold.id(), hold.eventSessionId());

        // Chỗ đã sang RESERVED trong database, khoá Redis hết giá trị (ADR-0015).
        UUID sessionId = hold.eventSessionId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gate.release(sessionId, hold.seatIds());
            }
        });

        return new Result(cmd.orderId(), sessionId, hold.seatIds());
    }
}
