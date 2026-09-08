// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketRepository implements TicketRepository {

    private static final String COLUMNS =
            """
            id, order_id, order_item_id, event_session_id, organization_id, user_id, session_seat_id,
            seat_code, zone_code, admission_type, seat_label, ticket_type_name, status,
            checked_in_at, checked_in_by
            """;

    private final JdbcTemplate jdbc;

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int issueAll(List<Ticket> tickets) {
        if (tickets.isEmpty()) {
            return 0;
        }
        // ON CONFLICT DO NOTHING trên order_item_id — chốt chặn cuối cùng chống phát hành trùng.
        // Bỏ qua chứ không ném: "vé này đã có" là kết quả ĐÚNG của một lần consumer chạy lại.
        int[] affected = jdbc.batchUpdate(
                """
                INSERT INTO tickets (id, order_id, order_item_id, event_session_id, organization_id, user_id,
                                     session_seat_id, seat_code, zone_code, admission_type, seat_label,
                                     ticket_type_name, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALID')
                ON CONFLICT (order_item_id) DO NOTHING
                """,
                tickets.stream()
                        .map(t -> new Object[] {
                            t.id(),
                            t.orderId(),
                            t.orderItemId(),
                            t.eventSessionId(),
                            t.organizationId(),
                            t.userId(),
                            t.sessionSeatId(),
                            t.seatCode(),
                            t.zoneCode(),
                            t.admissionType(),
                            t.seatLabel(),
                            t.ticketTypeName()
                        })
                        .toList());
        return Arrays.stream(affected).sum();
    }

    @Override
    public Optional<Ticket> findById(UUID ticketId) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM tickets WHERE id = ?", JdbcTicketRepository::map, ticketId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Ticket> findByOrder(UUID orderId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM tickets WHERE order_id = ? ORDER BY seat_code",
                JdbcTicketRepository::map,
                orderId);
    }

    @Override
    public List<Ticket> findByUser(UUID userId, int limit, int offset) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM tickets WHERE user_id = ? ORDER BY issued_at DESC LIMIT ? OFFSET ?",
                JdbcTicketRepository::map,
                userId,
                limit,
                offset);
    }

    @Override
    public boolean checkIn(UUID ticketId, UUID staffId, Instant at) {
        // WHERE status = 'VALID' nằm trong chính câu ghi. Hai máy quét cùng một vé trong cùng
        // một giây là chuyện bình thường ở cửa; chỉ một câu UPDATE thắng và trả về 1 dòng.
        return jdbc.update(
                        """
                        UPDATE tickets
                           SET status = 'CHECKED_IN', checked_in_at = ?, checked_in_by = ?
                         WHERE id = ? AND status = 'VALID'
                        """,
                        Timestamp.from(at),
                        staffId,
                        ticketId)
                == 1;
    }

    @Override
    public int revokeByOrder(UUID orderId, String reason, Instant at) {
        // Vé đã soát KHÔNG bị thu hồi: người ta đã vào cửa rồi, và đổi trạng thái sẽ xoá mất
        // bằng chứng đó.
        return jdbc.update(
                """
                UPDATE tickets SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?
                 WHERE order_id = ? AND status = 'VALID'
                """,
                Timestamp.from(at),
                reason,
                orderId);
    }

    private static Ticket map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp checkedInAt = rs.getTimestamp("checked_in_at");
        return Ticket.rehydrate(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("order_item_id", UUID.class),
                rs.getObject("event_session_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("session_seat_id", UUID.class),
                rs.getString("seat_code"),
                rs.getString("zone_code"),
                rs.getString("admission_type"),
                rs.getString("seat_label"),
                rs.getString("ticket_type_name"),
                TicketStatus.valueOf(rs.getString("status")),
                checkedInAt == null ? null : checkedInAt.toInstant(),
                rs.getObject("checked_in_by", UUID.class));
    }
}
