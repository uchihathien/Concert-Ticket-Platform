// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhả các giữ chỗ đã quá hạn — đường nhả thứ hai trong bốn đường của ADR-1014.
 *
 * <p>Chạy được nhiều instance song song: {@code claimExpired} dùng {@code FOR UPDATE SKIP LOCKED}
 * nên hai worker không bao giờ nhận cùng một giữ chỗ.
 *
 * <p>Khoá Redis vẫn được xoá tay dù đã có TTL: TTL của Redis và {@code expires_at} của database
 * không nhất thiết trùng nhau, và để ghế kẹt thêm vài giây trong đợt mở bán là mất doanh thu thật.
 */
@Service
public class ExpireHoldsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireHoldsJob.class);
    private static final int BATCH_SIZE = 200;

    private final SeatHoldRepository holds;
    private final SeatRepository seats;
    private final SessionInventoryRepository sessions;
    private final AvailabilityGate gate;
    private final AvailabilityPublisher publisher;
    private final Clock clock;

    public ExpireHoldsJob(
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

    /**
     * @return số giữ chỗ đã dọn — trả về để test khẳng định được, thay vì phải chờ và đoán
     */
    @Transactional
    public int runOnce() {
        List<SeatHold> expired = holds.claimExpired(clock.instant(), BATCH_SIZE);
        for (SeatHold hold : expired) {
            hold.expire();
            holds.updateStatus(hold.id(), hold.status(), clock.instant());
            seats.release(hold.seatIds());
            gate.release(hold.eventSessionId(), hold.seatIds());
            publisher.availabilityChanged(
                    hold.eventSessionId(), sessions.bumpAvailabilityVersion(hold.eventSessionId()));
        }
        if (!expired.isEmpty()) {
            log.info("Đã nhả {} lần giữ chỗ hết hạn", expired.size());
        }
        return expired.size();
    }
}
