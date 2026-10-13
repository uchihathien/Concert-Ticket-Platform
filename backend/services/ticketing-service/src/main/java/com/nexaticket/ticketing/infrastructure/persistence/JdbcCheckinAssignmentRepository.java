// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCheckinAssignmentRepository implements CheckinAssignmentRepository {

    private final JdbcTemplate jdbc;

    public JdbcCheckinAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAssigned(UUID organizationId, UUID userId, UUID eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM checkin_assignments WHERE organization_id = ? AND user_id = ? AND event_id = ?",
                Integer.class,
                organizationId,
                userId,
                eventId);
        return count != null && count > 0;
    }

    @Override
    public List<Assignment> find(UUID organizationId, UUID userId) {
        return jdbc.query(
                """
                SELECT user_id, event_id, assigned_by, assigned_at
                  FROM checkin_assignments
                 WHERE organization_id = ? AND (?::uuid IS NULL OR user_id = ?::uuid)
                 ORDER BY user_id, assigned_at, event_id
                """,
                (rs, i) -> new Assignment(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("assigned_by", UUID.class),
                        rs.getTimestamp("assigned_at").toInstant()),
                organizationId,
                userId,
                userId);
    }

    @Override
    public void replace(UUID organizationId, UUID userId, Collection<UUID> eventIds, UUID assignedBy, Instant at) {
        Set<UUID> wanted = new LinkedHashSet<>(eventIds);
        if (wanted.isEmpty()) {
            jdbc.update(
                    "DELETE FROM checkin_assignments WHERE organization_id = ? AND user_id = ?",
                    organizationId,
                    userId);
            return;
        }
        // Xoá phần thừa rồi thêm phần thiếu, KHÔNG xoá hết rồi chèn lại: dòng đã có giữ nguyên
        // người gán và thời điểm gán ban đầu — đó là thứ đối soát cần.
        jdbc.update(
                "DELETE FROM checkin_assignments WHERE organization_id = ? AND user_id = ? AND NOT (event_id = ANY (?))",
                organizationId,
                userId,
                wanted.toArray(UUID[]::new));
        jdbc.batchUpdate(
                """
                INSERT INTO checkin_assignments (organization_id, user_id, event_id, assigned_by, assigned_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, event_id) DO NOTHING
                """,
                wanted.stream()
                        .map(eventId -> new Object[] {organizationId, userId, eventId, assignedBy, Timestamp.from(at)})
                        .toList());
    }
}
