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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketRepository implements TicketRepository {

    private static final String COLUMNS =
            """
            id, order_id, order_item_id, event_session_id, organization_id, user_id, session_seat_id,
            seat_code, zone_code, admission_type, seat_label, ticket_type_name, holder_name, status,
            checked_in_at, checked_in_by, inside, entry_count, last_entry_at, last_entry_gate, last_exit_at
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
                                     ticket_type_name, holder_name, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALID')
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
                            t.ticketTypeName(),
                            t.holderName()
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
    public List<Ticket> findByCodePrefix(UUID organizationId, String codePrefix) {
        // `id::text LIKE 'abc%'` không dùng được index của khoá chính, nhưng mệnh đề tổ chức đã thu
        // tập về vé của một tổ chức (idx_ticket_org_issued) — cùng đánh đổi với màn tra cứu vé.
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM tickets WHERE organization_id = ? AND id::text LIKE ? LIMIT 2",
                JdbcTicketRepository::map,
                organizationId,
                codePrefix.toLowerCase(Locale.ROOT) + "%");
    }

    @Override
    public Optional<Integer> enter(UUID ticketId, UUID staffId, Instant at, String gate, boolean reentryAllowed) {
        // WHERE inside = FALSE nằm trong chính câu ghi. Hai máy quét cùng một vé trong cùng một
        // giây là chuyện bình thường ở cửa; chỉ một câu UPDATE thắng và trả về 1 dòng.
        //
        // COALESCE giữ checked_in_at / checked_in_by là lần vào ĐẦU TIÊN — màn tra cứu của ban tổ
        // chức và email "đã check-in" đều đọc nghĩa đó.
        Timestamp now = Timestamp.from(at);
        return jdbc
                .query(
                        """
                        UPDATE tickets
                           SET status = 'CHECKED_IN', inside = TRUE, entry_count = entry_count + 1,
                               checked_in_at = COALESCE(checked_in_at, ?),
                               checked_in_by = COALESCE(checked_in_by, ?),
                               last_entry_at = ?, last_entry_gate = ?
                         WHERE id = ? AND status <> 'REVOKED' AND inside = FALSE
                           AND (? OR entry_count = 0)
                        RETURNING entry_count
                        """,
                        (rs, i) -> rs.getInt(1),
                        now,
                        staffId,
                        now,
                        gate,
                        ticketId,
                        reentryAllowed)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Integer> forceEnter(UUID ticketId, Instant at, String gate) {
        return jdbc
                .query(
                        """
                        UPDATE tickets
                           SET entry_count = entry_count + 1, last_entry_at = ?, last_entry_gate = ?
                         WHERE id = ? AND status = 'CHECKED_IN' AND inside = TRUE
                        RETURNING entry_count
                        """,
                        (rs, i) -> rs.getInt(1),
                        Timestamp.from(at),
                        gate,
                        ticketId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean exit(UUID ticketId, Instant at) {
        return jdbc.update(
                        "UPDATE tickets SET inside = FALSE, last_exit_at = ? WHERE id = ? AND inside = TRUE",
                        Timestamp.from(at),
                        ticketId)
                == 1;
    }

    @Override
    public Presence presenceOf(UUID organizationId, UUID eventSessionId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FILTER (WHERE inside) AS inside,
                       count(*) FILTER (WHERE entry_count > 0) AS checked_in
                  FROM tickets
                 WHERE event_session_id = ? AND organization_id = ?
                """,
                (rs, i) -> new Presence(rs.getInt("inside"), rs.getInt("checked_in")),
                eventSessionId,
                organizationId);
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
        Ticket ticket = Ticket.rehydrate(
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
                rs.getString("holder_name"),
                TicketStatus.valueOf(rs.getString("status")),
                checkedInAt == null ? null : checkedInAt.toInstant(),
                rs.getObject("checked_in_by", UUID.class));
        return ticket.withPresence(
                rs.getBoolean("inside"),
                rs.getInt("entry_count"),
                instant(rs, "last_entry_at"),
                rs.getString("last_entry_gate"),
                instant(rs, "last_exit_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
