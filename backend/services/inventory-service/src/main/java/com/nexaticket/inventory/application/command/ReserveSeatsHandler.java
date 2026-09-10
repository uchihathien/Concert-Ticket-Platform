// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
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
 *
 * <p><b>Trả về giá và nhãn của từng chỗ, không chỉ id.</b> Ordering cộng tổng tiền từ chính những
 * giá này rồi sao nhãn vào dòng đơn hàng — đó là lý do giá không can thiệp được từ phía khách: nó
 * đi từ bản sao tồn kho của Inventory, không đi từ request. Trả về mỗi id sẽ buộc Ordering gọi
 * ngược lại để lấy từng thứ, thêm một chặng mạng vào đúng chỗ khách đang chờ màn hình.
 */
@Service
public class ReserveSeatsHandler {

    private final SeatHoldRepository holds;
    private final SeatRepository seats;
    private final SessionInventoryRepository sessions;
    private final AvailabilityGate gate;
    private final Clock clock;

    public ReserveSeatsHandler(
            SeatHoldRepository holds,
            SeatRepository seats,
            SessionInventoryRepository sessions,
            AvailabilityGate gate,
            Clock clock) {
        this.holds = holds;
        this.seats = seats;
        this.sessions = sessions;
        this.gate = gate;
        this.clock = clock;
    }

    public record Command(UUID orderId, UUID holdId, UUID userId) {}

    /**
     * @param seats gồm cả đơn vị ảo của vé đứng — với Ordering thì một vé đứng cũng là một dòng
     *     đơn hàng có giá như mọi dòng khác
     */
    public record Result(
            UUID orderId, UUID eventSessionId, UUID eventId, UUID organizationId, List<ReservedSeat> seats) {}

    /**
     * Một chỗ đã đặt, ở <b>tầng application</b>.
     *
     * <p>Trùng từng field với {@code SeatRepository.ReservedSeatRow} nhưng cố ý không dùng lại nó:
     * controller phải dựng được JSON trả về mà không import gì từ {@code domain}
     * (ArchitectureRules.hexagonalLayers). Cái giá là một phép sao chép tám dòng; cái được là tầng
     * interfaces không bao giờ ghim vào một cổng của domain, và cổng đó đổi được mà không kéo theo
     * REST.
     */
    public record ReservedSeat(
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            UUID ticketTypeId,
            String ticketTypeName,
            long priceVnd) {}

    @Transactional
    public Result handle(Command cmd) {
        Optional<UUID> already = holds.findHoldIdByOrder(cmd.orderId());
        if (already.isPresent()) {
            SeatHold done = holds.findById(already.get()).orElseThrow();
            return describe(cmd.orderId(), done);
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

        return describe(cmd.orderId(), hold);
    }

    /** Dựng kết quả trả về Ordering: id suất, tổ chức, và chi tiết từng chỗ. */
    private Result describe(UUID orderId, SeatHold hold) {
        SessionInventory session = sessions.findBySessionId(hold.eventSessionId())
                .orElseThrow(() ->
                        new ApiException(InventoryErrorCode.SESSION_NOT_FOUND, "Event session inventory disappeared"));

        List<ReservedSeat> detail = seats.detailsOf(hold.seatIds()).stream()
                .map(row -> new ReservedSeat(
                        row.sessionSeatId(),
                        row.seatCode(),
                        row.zoneCode(),
                        row.admissionType(),
                        row.seatLabel(),
                        row.ticketTypeId(),
                        row.ticketTypeName(),
                        row.priceVnd()))
                .toList();

        // eventId đi kèm vì Ordering phải gắn nó vào mọi sự kiện order.* — read model doanh thu
        // khoá theo suất diễn nhưng nhóm theo sự kiện, và không service nào khác trên đường
        // checkout biết cặp này.
        return new Result(orderId, hold.eventSessionId(), session.eventId(), session.organizationId(), detail);
    }
}
