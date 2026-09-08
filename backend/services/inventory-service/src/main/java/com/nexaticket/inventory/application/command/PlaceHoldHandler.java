// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.HoldRequest;
import com.nexaticket.inventory.domain.model.LimitViolation;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.model.StandingRequest;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Trái tim hệ thống: giữ chỗ.
 *
 * <p>Thứ tự các bước là cố ý, từ rẻ đến đắt — trong một đợt mở bán, phần lớn request sẽ thua cuộc,
 * và chúng nên dừng lại càng sớm càng tốt:
 *
 * <ol>
 *   <li>Đọc cấu hình suất, kiểm khung giờ bán và trần mỗi lần giữ chỗ — không chạm khoá nào
 *   <li>Advisory lock theo cặp (người dùng, suất diễn), rồi kiểm trần cộng dồn
 *   <li>Cổng Redis từ chối nhanh, chỉ cho vé ngồi
 *   <li>Ghi database — nơi bất biến thật sự được bảo vệ
 *   <li>Bump version ở bước cuối, và chỉ bắn AvailabilityChanged sau commit
 * </ol>
 *
 * <p><b>Redis không phải chốt chặn oversell.</b> Nếu Redis để lọt (key hết hạn giữa chừng,
 * failover), bước 4 vẫn chặn được: {@code UPDATE ... WHERE status = 'AVAILABLE'} trả về ít hàng hơn
 * yêu cầu, hoặc unique index {@code uq_hold_item_active} nổ. Database luôn thắng.
 */
@Service
public class PlaceHoldHandler {

    private static final Logger log = LoggerFactory.getLogger(PlaceHoldHandler.class);

    private final SessionInventoryRepository sessions;
    private final SeatRepository seats;
    private final SeatHoldRepository holds;
    private final AvailabilityGate gate;
    private final AvailabilityPublisher publisher;
    private final Clock clock;

    public PlaceHoldHandler(
            SessionInventoryRepository sessions,
            SeatRepository seats,
            SeatHoldRepository holds,
            AvailabilityGate gate,
            AvailabilityPublisher publisher,
            Clock clock) {
        this.sessions = sessions;
        this.seats = seats;
        this.holds = holds;
        this.gate = gate;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Yêu cầu giữ chỗ ở dạng dữ liệu thuần.
     *
     * <p>Cố ý <b>không</b> mang {@link HoldRequest}: controller chỉ được nói chuyện với tầng
     * application, và việc dựng value object của domain — kèm khử trùng lặp ghế và gộp dòng cùng
     * zone — là việc của tầng này. Nếu controller tự dựng, luật nghiệp vụ đó sẽ chạy hay không tuỳ
     * theo ai gọi.
     *
     * @param eventSessionId suất diễn
     * @param userId khách đang giữ chỗ
     * @param seatIds các chỗ ngồi khách chỉ đích danh
     * @param standing số lượng vé đứng theo zone
     */
    public record Command(UUID eventSessionId, UUID userId, List<UUID> seatIds, List<StandingLine> standing) {

        public record StandingLine(String zoneCode, int quantity) {}

        HoldRequest toDomain() {
            return new HoldRequest(
                    seatIds == null ? List.of() : seatIds,
                    standing == null
                            ? List.of()
                            : standing.stream()
                                    .map(line -> new StandingRequest(line.zoneCode(), line.quantity()))
                                    .toList());
        }
    }

    /**
     * @param holdId mã giữ chỗ, dùng để tạo đơn ở bước sau
     * @param expiresAt hết hạn thì chỗ tự nhả
     * @param seatIds toàn bộ đơn vị đã giữ, gồm cả đơn vị ảo của vé đứng
     * @param availabilityVersion version mới của suất diễn
     */
    public record Result(UUID holdId, Instant expiresAt, List<UUID> seatIds, long availabilityVersion) {}

    @Transactional
    public Result handle(Command cmd) {
        Instant now = clock.instant();
        HoldRequest request = cmd.toDomain();

        SessionInventory inventory = sessions.findBySessionId(cmd.eventSessionId())
                .orElseThrow(() -> new ApiException(InventoryErrorCode.SESSION_NOT_FOUND, "Event session not found"));

        if (!inventory.isSalesOpenAt(now)) {
            throw new ApiException(
                    InventoryErrorCode.SALES_CLOSED,
                    "Sales window is closed",
                    Map.of("opensAt", inventory.salesOpenAt(), "closesAt", inventory.salesCloseAt()));
        }

        inventory
                .limits()
                .checkHoldSize(request.seatedCount(), request.standingCount())
                .ifPresent(violation -> {
                    throw holdSizeRejected(violation, inventory);
                });

        // Từ đây trở đi chỉ các request của CÙNG một người trên CÙNG một suất là tuần tự.
        // Hai người khác nhau không bao giờ đụng nhau, nên thông lượng không đổi (ADR-1014 §3).
        seats.lockCustomerSession(cmd.userId(), cmd.eventSessionId());

        int alreadyHeld = seats.countUnitsHeldBy(cmd.eventSessionId(), cmd.userId());
        inventory.limits().checkCustomerTotal(alreadyHeld, request.totalUnits()).ifPresent(violation -> {
            throw new ApiException(
                    InventoryErrorCode.of(violation),
                    "Per-customer ticket limit exceeded",
                    Map.of(
                            "limit", inventory.limits().maxTicketsPerCustomer(),
                            "used", alreadyHeld,
                            "remaining", inventory.limits().remainingFor(alreadyHeld)));
        });

        UUID holdId = UUID.randomUUID();
        List<UUID> acquired = new ArrayList<>();

        if (request.hasSeated()) {
            acquired.addAll(acquireSeated(cmd, inventory, holdId, request));
        }
        for (StandingRequest standing : request.standing()) {
            acquired.addAll(allocateStanding(cmd, standing));
        }

        SeatHold hold =
                SeatHold.active(holdId, cmd.eventSessionId(), cmd.userId(), inventory.holdExpiryFrom(now), acquired);
        holds.save(hold);

        // Bước cuối cùng: UPDATE vào hàng của suất diễn giữ khoá tới COMMIT, nên đặt ở đây
        // để cửa sổ giữ khoá ngắn nhất có thể.
        long version = sessions.bumpAvailabilityVersion(cmd.eventSessionId());
        afterCommit(() -> publisher.availabilityChanged(cmd.eventSessionId(), version));

        return new Result(holdId, hold.expiresAt(), acquired, version);
    }

    private ApiException holdSizeRejected(LimitViolation violation, SessionInventory inventory) {
        String detail =
                switch (violation) {
                    case EMPTY_REQUEST -> "Hold must contain at least one seat or standing ticket";
                    case TOO_MANY_SEATED -> "Too many seated tickets in one hold";
                    case TOO_MANY_STANDING -> "Too many standing tickets in one hold";
                    default -> "Too many tickets in one hold";
                };
        return new ApiException(
                InventoryErrorCode.of(violation),
                detail,
                Map.of(
                        "maxSeatedPerHold", inventory.limits().maxSeatedPerHold(),
                        "maxStandingPerHold", inventory.limits().maxStandingPerHold(),
                        "maxUnitsPerHold", inventory.limits().maxUnitsPerHold()));
    }

    private List<UUID> acquireSeated(Command cmd, SessionInventory inventory, UUID holdId, HoldRequest request) {
        int ttlSeconds = (int) inventory.holdTtl().toSeconds();
        List<UUID> taken;
        try {
            taken = gate.tryAcquire(cmd.eventSessionId(), request.seatIds(), holdId, cmd.userId(), ttlSeconds);
        } catch (AvailabilityGate.GateUnavailableException e) {
            // Không fallback DB-only (ADR-0004): thà từ chối một request còn hơn bán trùng một ghế.
            log.warn("Cổng khả dụng không sẵn sàng cho suất {}", cmd.eventSessionId(), e);
            throw new ApiException(
                    InventoryErrorCode.INVENTORY_UNAVAILABLE, "Seat gate temporarily unavailable, retry shortly");
        }
        if (!taken.isEmpty()) {
            throw seatTaken(taken);
        }

        // Redis đã nhận, nhưng database mới quyết định. Rollback ở bất kỳ đâu sau đây — kể cả
        // unique index nổ — đều phải trả key về, nếu không ghế bị kẹt tới hết TTL.
        releaseGateOnRollback(cmd.eventSessionId(), request.seatIds());

        int affected = seats.holdSeated(cmd.eventSessionId(), request.seatIds(), cmd.userId());
        if (affected != request.seatedCount()) {
            // Redis và database lệch nhau — hiếm, và database thắng.
            log.info(
                    "Cổng Redis cho qua nhưng database từ chối: suất {}, xin {} chỗ, giữ được {}",
                    cmd.eventSessionId(),
                    request.seatedCount(),
                    affected);
            throw seatTaken(List.of());
        }
        return request.seatIds();
    }

    private ApiException seatTaken(List<UUID> taken) {
        return new ApiException(
                InventoryErrorCode.SEAT_UNAVAILABLE,
                "One or more seats were just taken",
                Map.of("unavailableSeatIds", taken));
    }

    private List<UUID> allocateStanding(Command cmd, StandingRequest standing) {
        List<UUID> allocated =
                seats.allocateStanding(cmd.eventSessionId(), standing.zoneCode(), standing.quantity(), cmd.userId());
        if (allocated.size() < standing.quantity()) {
            throw new ApiException(
                    InventoryErrorCode.ZONE_SOLD_OUT,
                    "Standing zone does not have enough inventory",
                    Map.of(
                            "zoneCode", standing.zoneCode(),
                            "requested", standing.quantity(),
                            "available", allocated.size()));
        }
        return allocated;
    }

    private void releaseGateOnRollback(UUID eventSessionId, List<UUID> seatIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    gate.release(eventSessionId, seatIds);
                }
            }
        });
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
