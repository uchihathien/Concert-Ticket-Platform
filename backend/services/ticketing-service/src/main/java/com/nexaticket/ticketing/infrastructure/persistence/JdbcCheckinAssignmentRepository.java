// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCheckinAssignmentRepository implements CheckinAssignmentRepository {

    private static final String COLUMNS = "id, user_id, event_id, event_session_id, assigned_by, assigned_at";

    private final JdbcTemplate jdbc;

    public JdbcCheckinAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Assignment> forUserEvent(UUID organizationId, UUID userId, UUID eventId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM checkin_assignments WHERE organization_id = ? AND user_id = ? AND event_id = ?",
                JdbcCheckinAssignmentRepository::map,
                organizationId,
                userId,
                eventId);
    }

    @Override
    public List<Assignment> find(UUID organizationId, UUID userId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + """
                         FROM checkin_assignments
                         WHERE organization_id = ? AND (?::uuid IS NULL OR user_id = ?::uuid)
                         ORDER BY user_id, assigned_at, event_id, event_session_id NULLS FIRST
                        """,
                JdbcCheckinAssignmentRepository::map,
                organizationId,
                userId,
                userId);
    }

    @Override
    public void insert(UUID organizationId, UUID userId, Collection<Scope> scopes, UUID assignedBy, Instant at) {
        if (scopes.isEmpty()) {
            return;
        }
        // ON CONFLICT dựa vào index biểu thức uq_checkin_assignment_scope (V0106): hai lần bấm Lưu
        // liên tiếp không sinh dòng trùng, và dòng đã có giữ nguyên người gán ban đầu.
        jdbc.batchUpdate(
                """
                INSERT INTO checkin_assignments
                       (id, organization_id, user_id, event_id, event_session_id, assigned_by, assigned_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, event_id, COALESCE(event_session_id, '00000000-0000-0000-0000-000000000000'::uuid))
                DO NOTHING
                """,
                scopes.stream()
                        .map(scope -> new Object[] {
                            organizationId,
                            userId,
                            scope.eventId(),
                            scope.eventSessionId(),
                            assignedBy,
                            Timestamp.from(at)
                        })
                        .toList());
    }

    @Override
    public void delete(UUID organizationId, UUID userId, Collection<UUID> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return;
        }
        jdbc.update(
                "DELETE FROM checkin_assignments WHERE organization_id = ? AND user_id = ? AND id = ANY (?)",
                organizationId,
                userId,
                assignmentIds.toArray(UUID[]::new));
    }

    private static Assignment map(ResultSet rs, int row) throws SQLException {
        return new Assignment(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("event_session_id", UUID.class),
                rs.getObject("assigned_by", UUID.class),
                rs.getTimestamp("assigned_at").toInstant());
    }
}
