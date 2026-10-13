// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.application.query.GateStats;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Số liệu soát vé đọc thẳng từ {@code tickets} và {@code checkin_log}.
 *
 * <h3>Phạm vi tổ chức đi qua bảng vé, không qua cột của nhật ký</h3>
 *
 * {@code checkin_log.organization_id} chỉ có từ V0102 và để trống ở các dòng cũ hơn, nên lọc bằng nó
 * sẽ làm rơi lịch sử. Thay vào đó, nhật ký được giới hạn trong những suất mà tổ chức CÓ VÉ
 * ({@code scope}). Cách này cũng là chốt an toàn: mã suất của tổ chức khác đưa vào bộ lọc không khớp
 * dòng nào, vì nó không nằm trong {@code scope}.
 *
 * <p>Sau {@code scope}, database đi vào nhật ký bằng {@code idx_checkin_session}
 * ({@code event_session_id, scanned_at}).
 */
@Repository
public class JdbcGateStatsReader implements GateStats.Reader {

    private static final String ACCEPTED_IN = "result = 'ACCEPTED' AND direction = 'IN'";
    private static final String REJECTED = "result NOT IN ('ACCEPTED', 'EXITED')";
    private static final String LOCAL = "(scanned_at AT TIME ZONE 'Asia/Ho_Chi_Minh')";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGateStatsReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lọc bằng {@code organization_id}, cột đầu của {@code idx_ticket_org_issued}. */
    @Override
    public List<GateStats.SessionPresence> presenceBySession(UUID organizationId) {
        return jdbc.query(
                """
                SELECT event_session_id,
                       COUNT(*) FILTER (WHERE status <> 'REVOKED') AS tickets_issued,
                       COUNT(*) FILTER (WHERE entry_count > 0)     AS arrived,
                       COUNT(*) FILTER (WHERE inside)              AS inside,
                       COALESCE(SUM(entry_count), 0)               AS entries
                  FROM tickets
                 WHERE organization_id = :organizationId
                 GROUP BY event_session_id
                """,
                new MapSqlParameterSource("organizationId", organizationId),
                (rs, i) -> new GateStats.SessionPresence(
                        rs.getObject("event_session_id", UUID.class),
                        rs.getLong("tickets_issued"),
                        rs.getLong("arrived"),
                        rs.getLong("inside"),
                        rs.getLong("entries")));
    }

    @Override
    public GateStats.LogTotals totals(GateStats.LogFilter filter) {
        String sql = logs(filter)
                + """
                SELECT COUNT(*)                                      AS scans,
                       COUNT(*) FILTER (WHERE %1$s)                  AS entries,
                       COUNT(DISTINCT ticket_id) FILTER (WHERE %1$s) AS unique_tickets,
                       COUNT(*) FILTER (WHERE result = 'EXITED')     AS exits,
                       COUNT(*) FILTER (WHERE %2$s)                  AS rejected
                  FROM logs
                """
                        .formatted(ACCEPTED_IN, REJECTED);
        return jdbc.queryForObject(
                sql,
                params(filter),
                (rs, i) -> new GateStats.LogTotals(
                        rs.getLong("scans"),
                        rs.getLong("entries"),
                        rs.getLong("unique_tickets"),
                        rs.getLong("exits"),
                        rs.getLong("rejected")));
    }

    @Override
    public List<GateStats.Count> rejectedByReason(GateStats.LogFilter filter) {
        return counts(filter, "result", REJECTED);
    }

    @Override
    public List<GateStats.Count> entriesByMethod(GateStats.LogFilter filter) {
        return counts(filter, "entry_method", ACCEPTED_IN);
    }

    private List<GateStats.Count> counts(GateStats.LogFilter filter, String column, String condition) {
        String sql = logs(filter)
                + "SELECT %1$s AS key, COUNT(*) AS n FROM logs WHERE %2$s GROUP BY %1$s ORDER BY n DESC, key"
                        .formatted(column, condition);
        return jdbc.query(sql, params(filter), (rs, i) -> new GateStats.Count(rs.getString("key"), rs.getLong("n")));
    }

    @Override
    public List<GateStats.SessionFlow> flowBySession(GateStats.LogFilter filter) {
        String sql = logs(filter)
                + """
                , firsts AS (
                    SELECT event_session_id, ticket_id, MIN(scanned_at) AS first_at
                      FROM logs
                     WHERE %1$s AND ticket_id IS NOT NULL
                     GROUP BY event_session_id, ticket_id
                ), arrival AS (
                    SELECT event_session_id,
                           MIN(first_at)                                   AS first_entry_at,
                           MAX(first_at)                                   AS last_entry_at,
                           to_timestamp(AVG(EXTRACT(EPOCH FROM first_at))) AS average_entry_at
                      FROM firsts
                     GROUP BY event_session_id
                )
                SELECT l.event_session_id,
                       COUNT(*) FILTER (WHERE %1$s)                  AS entries,
                       COUNT(DISTINCT ticket_id) FILTER (WHERE %1$s) AS unique_tickets,
                       COUNT(*) FILTER (WHERE result = 'EXITED')     AS exits,
                       COUNT(*) FILTER (WHERE %2$s)                  AS rejected,
                       MIN(a.first_entry_at)                         AS first_entry_at,
                       MIN(a.last_entry_at)                          AS last_entry_at,
                       MIN(a.average_entry_at)                       AS average_entry_at
                  FROM logs l
                  LEFT JOIN arrival a ON a.event_session_id = l.event_session_id
                 GROUP BY l.event_session_id
                 ORDER BY first_entry_at DESC NULLS LAST, l.event_session_id
                """
                        .formatted(ACCEPTED_IN, REJECTED);
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new GateStats.SessionFlow(
                        rs.getObject("event_session_id", UUID.class),
                        rs.getLong("entries"),
                        rs.getLong("unique_tickets"),
                        rs.getLong("exits"),
                        rs.getLong("rejected"),
                        instant(rs, "first_entry_at"),
                        instant(rs, "last_entry_at"),
                        instant(rs, "average_entry_at")));
    }

    @Override
    public List<GateStats.ScannerRow> scanners(GateStats.LogFilter filter) {
        String sql = logs(filter)
                + """
                SELECT scanned_by,
                       COUNT(*)                                                  AS scans,
                       COUNT(*) FILTER (WHERE %1$s)                              AS accepted,
                       COUNT(*) FILTER (WHERE %2$s)                              AS rejected,
                       COUNT(*) FILTER (WHERE result = 'EXITED')                 AS exits,
                       COUNT(*) FILTER (WHERE %1$s AND entry_method = 'OVERRIDE') AS overrides,
                       COUNT(DISTINCT date_trunc('minute', scanned_at))          AS active_minutes,
                       MIN(scanned_at)                                           AS first_scan_at,
                       MAX(scanned_at)                                           AS last_scan_at
                  FROM logs
                 GROUP BY scanned_by
                 ORDER BY scans DESC, scanned_by
                """
                        .formatted(ACCEPTED_IN, REJECTED);
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new GateStats.ScannerRow(
                        rs.getObject("scanned_by", UUID.class),
                        rs.getLong("scans"),
                        rs.getLong("accepted"),
                        rs.getLong("rejected"),
                        rs.getLong("exits"),
                        rs.getLong("overrides"),
                        rs.getLong("active_minutes"),
                        instant(rs, "first_scan_at"),
                        instant(rs, "last_scan_at")));
    }

    @Override
    public List<GateStats.GateRow> gates(GateStats.LogFilter filter) {
        String sql = logs(filter)
                + """
                SELECT gate,
                       COUNT(*)                                  AS scans,
                       COUNT(*) FILTER (WHERE %1$s)              AS accepted,
                       COUNT(*) FILTER (WHERE %2$s)              AS rejected,
                       COUNT(*) FILTER (WHERE result = 'EXITED') AS exits
                  FROM logs
                 GROUP BY gate
                 ORDER BY scans DESC, gate
                """
                        .formatted(ACCEPTED_IN, REJECTED);
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new GateStats.GateRow(
                        rs.getString("gate"),
                        rs.getLong("scans"),
                        rs.getLong("accepted"),
                        rs.getLong("rejected"),
                        rs.getLong("exits")));
    }

    @Override
    public List<GateStats.Override> overrides(GateStats.LogFilter filter, int limit) {
        String sql = logs(filter)
                + """
                SELECT event_session_id, scanned_by, scanned_at, gate, note,
                       UPPER(LEFT(ticket_id::text, 8)) AS ticket_code
                  FROM logs
                 WHERE entry_method = 'OVERRIDE'
                 ORDER BY scanned_at DESC
                 LIMIT :limit
                """;
        return jdbc.query(
                sql,
                params(filter).addValue("limit", limit),
                (rs, i) -> new GateStats.Override(
                        rs.getObject("event_session_id", UUID.class),
                        rs.getObject("scanned_by", UUID.class),
                        instant(rs, "scanned_at"),
                        rs.getString("gate"),
                        rs.getString("note"),
                        rs.getString("ticket_code")));
    }

    @Override
    public List<GateStats.FlowPoint> timeline(GateStats.LogFilter filter, int limit) {
        String sql = logs(filter)
                + """
                SELECT date_trunc('hour', %3$s)
                           + FLOOR(EXTRACT(MINUTE FROM %3$s) / 15) * INTERVAL '15 minutes' AS bucket,
                       COUNT(*) FILTER (WHERE %1$s)              AS entries,
                       COUNT(*) FILTER (WHERE result = 'EXITED') AS exits,
                       COUNT(*) FILTER (WHERE %2$s)              AS rejected
                  FROM logs
                 GROUP BY 1
                 ORDER BY 1 DESC
                 LIMIT :limit
                """
                        .formatted(ACCEPTED_IN, REJECTED, LOCAL);
        return jdbc.query(
                sql,
                params(filter).addValue("limit", limit),
                (rs, i) -> new GateStats.FlowPoint(
                        rs.getTimestamp("bucket").toLocalDateTime(),
                        rs.getLong("entries"),
                        rs.getLong("exits"),
                        rs.getLong("rejected")));
    }

    /** {@code organization_id} nằm trong điều kiện: mã đơn của tổ chức khác không trả về tên nào. */
    @Override
    public List<GateStats.Buyer> holderNames(UUID organizationId, List<UUID> orderIds) {
        return jdbc.query(
                """
                SELECT order_id, MIN(holder_name) AS holder_name
                  FROM tickets
                 WHERE organization_id = :organizationId AND order_id IN (:orderIds)
                 GROUP BY order_id
                """,
                new MapSqlParameterSource("organizationId", organizationId).addValue("orderIds", orderIds),
                (rs, i) -> new GateStats.Buyer(rs.getObject("order_id", UUID.class), rs.getString("holder_name")));
    }

    @Override
    public List<GateStats.NoShowRow> noShows(UUID organizationId, List<UUID> sessionIds) {
        return jdbc.query(
                """
                SELECT event_session_id, ticket_type_name, COUNT(*) AS tickets
                  FROM tickets
                 WHERE organization_id = :organizationId
                   AND event_session_id IN (:sessionIds)
                   AND status <> 'REVOKED'
                   AND entry_count = 0
                 GROUP BY event_session_id, ticket_type_name
                """,
                new MapSqlParameterSource("organizationId", organizationId).addValue("sessionIds", sessionIds),
                (rs, i) -> new GateStats.NoShowRow(
                        rs.getObject("event_session_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        rs.getLong("tickets")));
    }

    @Override
    public List<GateStats.BuyerExportRow> buyersForExport(UUID organizationId, List<UUID> sessionIds, int limit) {
        String sql =
                """
                SELECT UPPER(LEFT(id::text, 8)) AS ticket_code, holder_name, event_session_id, ticket_type_name,
                       zone_code, seat_label, status, issued_at
                  FROM tickets
                 WHERE organization_id = :organizationId
                   AND status <> 'REVOKED'%s
                 ORDER BY event_session_id, holder_name, id
                 LIMIT :limit
                """
                        .formatted(sessionIds.isEmpty() ? "" : " AND event_session_id IN (:sessionIds)");
        return jdbc.query(
                sql,
                new MapSqlParameterSource("organizationId", organizationId)
                        .addValue("sessionIds", sessionIds)
                        .addValue("limit", limit),
                (rs, i) -> new GateStats.BuyerExportRow(
                        rs.getString("ticket_code"),
                        rs.getString("holder_name"),
                        rs.getObject("event_session_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        rs.getString("zone_code"),
                        rs.getString("seat_label"),
                        rs.getString("status"),
                        instant(rs, "issued_at")));
    }

    private static String logs(GateStats.LogFilter filter) {
        return """
                WITH scope AS (
                    SELECT DISTINCT event_session_id FROM tickets WHERE organization_id = :organizationId
                ), logs AS (
                    SELECT l.*
                      FROM checkin_log l
                      JOIN scope s ON s.event_session_id = l.event_session_id
                     WHERE l.scanned_at >= :from AND l.scanned_at < :to%s
                )
                """
                .formatted(filter.sessionIds().isEmpty() ? "" : " AND l.event_session_id IN (:sessionIds)");
    }

    private static MapSqlParameterSource params(GateStats.LogFilter filter) {
        return new MapSqlParameterSource()
                .addValue("organizationId", filter.organizationId())
                .addValue("from", Timestamp.from(filter.from()))
                .addValue("to", Timestamp.from(filter.to()))
                .addValue("sessionIds", filter.sessionIds());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
