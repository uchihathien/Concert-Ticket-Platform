// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCheckinLogRepository implements CheckinLogRepository {

    private final JdbcTemplate jdbc;

    public JdbcCheckinLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Scan scan) {
        jdbc.update(
                """
                INSERT INTO checkin_log (id, ticket_id, event_session_id, organization_id, scanned_by, device_id,
                                         direction, gate, entry_method, result, note, scanned_at, offline,
                                         client_scan_id, device_result, conflict, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()), ?, ?, ?, ?,
                        CASE WHEN ? THEN now() END)
                """,
                UUID.randomUUID(),
                scan.ticketId(),
                scan.eventSessionId(),
                scan.organizationId(),
                scan.staffId(),
                scan.deviceId(),
                scan.direction().name(),
                scan.gate(),
                scan.method().name(),
                scan.result().name(),
                scan.note(),
                scan.scannedAt() == null ? null : Timestamp.from(scan.scannedAt()),
                scan.offline(),
                scan.clientScanId(),
                scan.deviceResult(),
                scan.conflict(),
                scan.offline());
    }

    @Override
    public Optional<Previous> findByClientScanId(UUID clientScanId) {
        return jdbc
                .query(
                        "SELECT ticket_id, result, note, conflict FROM checkin_log WHERE client_scan_id = ?",
                        (rs, i) -> new Previous(
                                rs.getObject("ticket_id", UUID.class),
                                rs.getString("result"),
                                rs.getString("note"),
                                rs.getBoolean("conflict")),
                        clientScanId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Entry> conflicts(UUID organizationId, UUID eventSessionId, int limit) {
        return jdbc.query(
                """
                SELECT l.id, l.ticket_id, l.scanned_at, l.direction, l.result, l.entry_method, l.gate, l.note,
                       l.scanned_by, t.holder_name, t.seat_code, t.seat_label, t.ticket_type_name
                  FROM checkin_log l
                  LEFT JOIN tickets t ON t.id = l.ticket_id
                 WHERE l.conflict AND l.event_session_id = ? AND l.organization_id = ?
                 ORDER BY l.scanned_at DESC
                 LIMIT ?
                """,
                JdbcCheckinLogRepository::entry,
                eventSessionId,
                organizationId,
                limit);
    }

    private static Entry entry(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Entry(
                rs.getObject("id", UUID.class),
                rs.getObject("ticket_id", UUID.class),
                rs.getTimestamp("scanned_at").toInstant(),
                rs.getString("direction"),
                rs.getString("result"),
                rs.getString("entry_method"),
                rs.getString("gate"),
                rs.getString("note"),
                rs.getObject("scanned_by", UUID.class),
                rs.getString("holder_name"),
                rs.getString("seat_code"),
                rs.getString("seat_label"),
                rs.getString("ticket_type_name"));
    }

    @Override
    public ShiftTally tally(UUID staffId, UUID eventSessionId, Instant from, Instant to) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FILTER (WHERE direction = 'IN' AND result = 'ACCEPTED')         AS entries,
                       count(*) FILTER (WHERE result = 'EXITED')                                AS exits,
                       count(*) FILTER (WHERE result NOT IN ('ACCEPTED', 'EXITED'))             AS rejected,
                       count(*) FILTER (WHERE entry_method = 'OVERRIDE' AND result = 'ACCEPTED') AS overrides,
                       count(*) FILTER (WHERE offline)                                          AS offline,
                       count(*) FILTER (WHERE entry_method = 'LOOKUP' AND result = 'ACCEPTED')   AS lookups
                  FROM checkin_log
                 WHERE scanned_by = ? AND event_session_id = ? AND scanned_at >= ? AND scanned_at <= ?
                """,
                (rs, i) -> new ShiftTally(
                        rs.getInt("entries"),
                        rs.getInt("exits"),
                        rs.getInt("rejected"),
                        rs.getInt("overrides"),
                        rs.getInt("offline"),
                        rs.getInt("lookups")),
                staffId,
                eventSessionId,
                java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(to));
    }

    @Override
    public List<Entry> history(UUID organizationId, UUID eventSessionId, String query, int limit, int offset) {
        // COALESCE: dòng ghi trước khi có cột organization_id thì lấy tổ chức của vé. Dòng không
        // gắn vé nào VÀ không có tổ chức (mã giả quét từ trước) không thuộc về ai, nên không hiện.
        StringBuilder sql = new StringBuilder(
                """
                SELECT l.id, l.ticket_id, l.scanned_at, l.direction, l.result, l.entry_method, l.gate, l.note,
                       l.scanned_by, t.holder_name, t.seat_code, t.seat_label, t.ticket_type_name
                  FROM checkin_log l
                  LEFT JOIN tickets t ON t.id = l.ticket_id
                 WHERE l.event_session_id = ?
                   AND COALESCE(l.organization_id, t.organization_id) = ?
                """);
        List<Object> params = new ArrayList<>(List.of(eventSessionId, organizationId));
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            sql.append(" AND (t.holder_name ILIKE ? OR t.id::text ILIKE ? OR t.seat_code ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        sql.append(" ORDER BY l.scanned_at DESC, l.id LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbc.query(
                sql.toString(),
                (rs, i) -> {
                    Timestamp scannedAt = rs.getTimestamp("scanned_at");
                    return new Entry(
                            rs.getObject("id", UUID.class),
                            rs.getObject("ticket_id", UUID.class),
                            scannedAt.toInstant(),
                            rs.getString("direction"),
                            rs.getString("result"),
                            rs.getString("entry_method"),
                            rs.getString("gate"),
                            rs.getString("note"),
                            rs.getObject("scanned_by", UUID.class),
                            rs.getString("holder_name"),
                            rs.getString("seat_code"),
                            rs.getString("seat_label"),
                            rs.getString("ticket_type_name"));
                },
                params.toArray());
    }
}
