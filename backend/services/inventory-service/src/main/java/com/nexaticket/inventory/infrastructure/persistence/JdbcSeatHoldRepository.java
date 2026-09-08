// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.persistence;

import com.nexaticket.inventory.domain.model.HoldStatus;
import com.nexaticket.inventory.domain.model.SeatHold;
import com.nexaticket.inventory.domain.port.SeatHoldRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSeatHoldRepository implements SeatHoldRepository {

    private final JdbcTemplate jdbc;

    public JdbcSeatHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SeatHold hold) {
        jdbc.update(
                """
                INSERT INTO seat_holds (id, event_session_id, user_id, status, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                hold.id(),
                hold.eventSessionId(),
                hold.userId(),
                hold.status().name(),
                Timestamp.from(hold.expiresAt()));

        try {
            jdbc.batchUpdate(
                    "INSERT INTO seat_hold_items (id, hold_id, session_seat_id, status) VALUES (?, ?, ?, 'ACTIVE')",
                    hold.seatIds().stream()
                            .map(seatId -> new Object[] {UUID.randomUUID(), hold.id(), seatId})
                            .toList());
        } catch (DuplicateKeyException e) {
            // uq_hold_item_active — chốt chặn cuối cùng chống oversell. Đến được đây nghĩa là
            // cổng Redis đã để lọt; đúng như thiết kế, database vẫn chặn được.
            throw new SeatAlreadyHeldException(e);
        }
    }

    @Override
    public Optional<SeatHold> findById(UUID holdId) {
        List<SeatHold> found = jdbc.query(
                """
                SELECT id, event_session_id, user_id, status, expires_at
                  FROM seat_holds WHERE id = ?
                """,
                (rs, i) -> SeatHold.rehydrate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        seatIdsOf(rs.getObject("id", UUID.class)),
                        HoldStatus.valueOf(rs.getString("status"))),
                holdId);
        return found.stream().findFirst();
    }

    private List<UUID> seatIdsOf(UUID holdId) {
        return jdbc.query(
                "SELECT session_seat_id FROM seat_hold_items WHERE hold_id = ? ORDER BY session_seat_id",
                (rs, i) -> rs.getObject("session_seat_id", UUID.class),
                holdId);
    }

    @Override
    public void updateStatus(UUID holdId, HoldStatus status, Instant at) {
        jdbc.update(
                "UPDATE seat_holds SET status = ?, released_at = ? WHERE id = ?",
                status.name(),
                status == HoldStatus.ACTIVE ? null : Timestamp.from(at),
                holdId);
        // Nhả dòng khỏi unique index để chỗ dùng lại được nếu quay về AVAILABLE.
        jdbc.update(
                "UPDATE seat_hold_items SET status = ? WHERE hold_id = ? AND status = 'ACTIVE'",
                status == HoldStatus.CONVERTED ? "CONVERTED" : "RELEASED",
                holdId);
    }

    @Override
    public List<SeatHold> claimExpired(Instant now, int batchSize) {
        // SKIP LOCKED để nhiều instance worker chạy song song mà không giẫm chân nhau.
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM seat_holds
                 WHERE status = 'ACTIVE' AND expires_at <= ?
                 ORDER BY expires_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                Timestamp.from(now),
                batchSize);
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public void createReservation(UUID orderId, UUID holdId, UUID eventSessionId) {
        jdbc.update(
                """
                INSERT INTO seat_reservations (order_id, hold_id, event_session_id, status)
                VALUES (?, ?, ?, 'RESERVED')
                """,
                orderId,
                holdId,
                eventSessionId);
    }

    @Override
    public Optional<UUID> findHoldIdByOrder(UUID orderId) {
        return jdbc
                .query(
                        "SELECT hold_id FROM seat_reservations WHERE order_id = ?",
                        (rs, i) -> rs.getObject("hold_id", UUID.class),
                        orderId)
                .stream()
                .findFirst();
    }

    @Override
    public int updateReservationStatus(UUID orderId, String status, Instant at) {
        // WHERE status = 'RESERVED' làm cho lệnh này idempotent: gọi lần hai trả 0 hàng,
        // caller dừng lại và không ghi đè gì.
        return jdbc.update(
                """
                UPDATE seat_reservations SET status = ?, settled_at = ?
                 WHERE order_id = ? AND status = 'RESERVED'
                """,
                status,
                Timestamp.from(at),
                orderId);
    }
}
