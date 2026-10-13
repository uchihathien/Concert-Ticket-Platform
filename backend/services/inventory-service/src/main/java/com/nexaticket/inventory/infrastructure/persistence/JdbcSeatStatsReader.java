// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.persistence;

import com.nexaticket.inventory.application.query.SeatStats;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đếm chỗ theo loại vé của mọi suất thuộc một tổ chức.
 *
 * <p>{@code session_inventory} có một dòng cho mỗi suất nên lọc theo tổ chức ở đó là rẻ; từ mỗi
 * suất database đi vào {@code session_seats} bằng {@code idx_seat_session_status}.
 */
@Repository
public class JdbcSeatStatsReader implements SeatStats.Reader {

    private final JdbcTemplate jdbc;

    public JdbcSeatStatsReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SeatStats.TicketTypeStock> stockByTicketType(UUID organizationId) {
        return jdbc.query(
                """
                SELECT si.event_id, s.event_session_id, s.ticket_type_id,
                       MAX(s.ticket_type_name)                                   AS ticket_type_name,
                       MAX(s.price_vnd)                                          AS price_vnd,
                       COUNT(*)                                                  AS capacity,
                       COUNT(*) FILTER (WHERE s.status = 'SOLD')                 AS sold,
                       COUNT(*) FILTER (WHERE s.status IN ('HELD', 'RESERVED'))  AS held,
                       COUNT(*) FILTER (WHERE s.status = 'AVAILABLE')            AS available,
                       COUNT(*) FILTER (WHERE s.status = 'BLOCKED')              AS blocked
                  FROM session_inventory si
                  JOIN session_seats s ON s.event_session_id = si.event_session_id
                 WHERE si.organization_id = ?
                 GROUP BY si.event_id, s.event_session_id, s.ticket_type_id
                 ORDER BY si.event_id, s.event_session_id, price_vnd DESC
                """,
                (rs, i) -> new SeatStats.TicketTypeStock(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("event_session_id", UUID.class),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        rs.getLong("price_vnd"),
                        rs.getLong("capacity"),
                        rs.getLong("sold"),
                        rs.getLong("held"),
                        rs.getLong("available"),
                        rs.getLong("blocked")),
                organizationId);
    }

    /** Đi từ {@code idx_hold_expiry} (chỉ các lượt giữ còn ACTIVE), nên không quét lịch sử giữ chỗ. */
    @Override
    public List<SeatStats.ExpiringHolds> holdsExpiringWithin(UUID organizationId, int minutes) {
        return jdbc.query(
                """
                SELECT h.event_session_id, COUNT(hi.id) AS seats
                  FROM seat_holds h
                  JOIN session_inventory si ON si.event_session_id = h.event_session_id
                  JOIN seat_hold_items hi ON hi.hold_id = h.id AND hi.status = 'ACTIVE'
                 WHERE h.status = 'ACTIVE'
                   AND h.expires_at > now()
                   AND h.expires_at <= now() + make_interval(mins => ?)
                   AND si.organization_id = ?
                 GROUP BY h.event_session_id
                """,
                (rs, i) ->
                        new SeatStats.ExpiringHolds(rs.getObject("event_session_id", UUID.class), rs.getLong("seats")),
                minutes,
                organizationId);
    }
}
