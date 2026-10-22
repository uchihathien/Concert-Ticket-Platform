// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.port.CheckinBundleReader;
import com.nexaticket.ticketing.domain.port.IdentityPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCheckinBundleReader implements CheckinBundleReader {

    /** Lần đổi trạng thái gần nhất của một vé — không có cột updated_at, nên lấy mốc muộn nhất. */
    private static final String CHANGED_AT =
            "GREATEST(t.issued_at, t.checked_in_at, t.last_entry_at, t.last_exit_at, t.revoked_at)";

    private static final String SELECT =
            """
            SELECT t.id, t.ticket_type_name, t.zone_code, t.seat_code, t.seat_label, t.holder_name,
                   c.email, c.phone, t.status, t.payment_status, t.inside, t.entry_count,
                   t.last_entry_at, t.last_entry_gate, %s AS changed_at
              FROM tickets t
              LEFT JOIN ticket_holder_contacts c ON c.user_id = t.user_id
             WHERE t.organization_id = ? AND t.event_session_id = ?
            """
                    .formatted(CHANGED_AT);

    private final JdbcTemplate jdbc;

    public JdbcCheckinBundleReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BundleTicket> ticketsOf(UUID organizationId, UUID eventSessionId, Instant since) {
        if (since == null) {
            return jdbc.query(
                    SELECT + " ORDER BY t.issued_at, t.id",
                    JdbcCheckinBundleReader::row,
                    organizationId,
                    eventSessionId);
        }
        return jdbc.query(
                SELECT + " AND " + CHANGED_AT + " > ? ORDER BY t.issued_at, t.id",
                JdbcCheckinBundleReader::row,
                organizationId,
                eventSessionId,
                Timestamp.from(since));
    }

    @Override
    public List<BundleTicket> search(
            UUID organizationId, UUID eventSessionId, String folded, String digits, int limit) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" AND (vn_fold(t.holder_name) LIKE ? OR lower(c.email) LIKE ? OR lower(t.id::text) LIKE ?");
        List<Object> params = new ArrayList<>(List.of(organizationId, eventSessionId));
        String like = "%" + escape(folded) + "%";
        params.add(like);
        params.add(like);
        params.add(escape(folded) + "%");
        if (digits.length() >= 3) {
            sql.append(" OR regexp_replace(coalesce(c.phone, ''), '\\D', '', 'g') LIKE ?");
            params.add("%" + digits + "%");
        }
        sql.append(") ORDER BY t.holder_name NULLS LAST, t.seat_code LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), JdbcCheckinBundleReader::row, params.toArray());
    }

    @Override
    public List<UUID> holdersNeedingContacts(UUID organizationId, UUID eventSessionId, Instant olderThan) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT t.user_id
                  FROM tickets t
                  LEFT JOIN ticket_holder_contacts c ON c.user_id = t.user_id
                 WHERE t.organization_id = ? AND t.event_session_id = ?
                   AND (c.user_id IS NULL OR c.refreshed_at < ?)
                """,
                UUID.class,
                organizationId,
                eventSessionId,
                Timestamp.from(olderThan));
    }

    @Override
    public void saveContacts(Collection<IdentityPort.Contact> contacts, Instant at) {
        for (IdentityPort.Contact contact : contacts) {
            jdbc.update(
                    """
                    INSERT INTO ticket_holder_contacts (user_id, email, phone, full_name, refreshed_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET email = excluded.email, phone = excluded.phone,
                                                        full_name = excluded.full_name,
                                                        refreshed_at = excluded.refreshed_at
                    """,
                    contact.userId(),
                    contact.email(),
                    contact.phone(),
                    contact.fullName(),
                    Timestamp.from(at));
        }
    }

    /** Ký tự đại diện của LIKE trong chuỗi người dùng gõ phải là chữ thường, không phải đại diện. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BundleTicket row(ResultSet rs, int rowNum) throws SQLException {
        return new BundleTicket(
                rs.getObject("id", UUID.class),
                rs.getString("ticket_type_name"),
                rs.getString("zone_code"),
                rs.getString("seat_code"),
                rs.getString("seat_label"),
                rs.getString("holder_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getBoolean("inside"),
                rs.getInt("entry_count"),
                instant(rs, "last_entry_at"),
                rs.getString("last_entry_gate"),
                instant(rs, "changed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
