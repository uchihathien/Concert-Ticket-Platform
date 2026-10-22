// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.port.ShiftRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcShiftRepository implements ShiftRepository {

    private static final String SHIFT_SELECT =
            """
            SELECT s.id, s.organization_id, s.event_id, s.event_session_id, s.gate_id, g.name AS gate_name,
                   s.starts_at, s.ends_at
              FROM checkin_shifts s
              JOIN checkin_gates g ON g.id = s.gate_id
            """;

    private static final String SESSION_SELECT =
            "SELECT id, shift_id, user_id, device_id, started_at, ended_at FROM checkin_shift_sessions";

    private final JdbcTemplate jdbc;

    public JdbcShiftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createGate(Gate gate) {
        jdbc.update(
                "INSERT INTO checkin_gates (id, organization_id, event_id, name, created_at) VALUES (?, ?, ?, ?, ?)",
                gate.id(),
                gate.organizationId(),
                gate.eventId(),
                gate.name(),
                Timestamp.from(gate.createdAt()));
    }

    @Override
    public Optional<Gate> findGate(UUID organizationId, UUID gateId) {
        return jdbc
                .query(
                        "SELECT id, organization_id, event_id, name, created_at FROM checkin_gates"
                                + " WHERE organization_id = ? AND id = ?",
                        JdbcShiftRepository::gate,
                        organizationId,
                        gateId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Gate> gatesOf(UUID organizationId, UUID eventId) {
        return jdbc.query(
                "SELECT id, organization_id, event_id, name, created_at FROM checkin_gates"
                        + " WHERE organization_id = ? AND event_id = ? ORDER BY name",
                JdbcShiftRepository::gate,
                organizationId,
                eventId);
    }

    @Override
    public void renameGate(UUID gateId, String name) {
        jdbc.update("UPDATE checkin_gates SET name = ? WHERE id = ?", name, gateId);
    }

    @Override
    public boolean deleteGateIfUnused(UUID gateId) {
        return jdbc.update(
                        "DELETE FROM checkin_gates g WHERE g.id = ?"
                                + " AND NOT EXISTS (SELECT 1 FROM checkin_shifts s WHERE s.gate_id = g.id)",
                        gateId)
                > 0;
    }

    @Override
    public void createShift(Shift shift) {
        jdbc.update(
                """
                INSERT INTO checkin_shifts (id, organization_id, event_id, event_session_id, gate_id, starts_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                shift.id(),
                shift.organizationId(),
                shift.eventId(),
                shift.eventSessionId(),
                shift.gateId(),
                Timestamp.from(shift.startsAt()),
                Timestamp.from(shift.endsAt()));
        replaceStaff(shift.id(), shift.staffIds());
    }

    @Override
    public void updateShift(UUID shiftId, UUID gateId, Instant startsAt, Instant endsAt) {
        jdbc.update(
                "UPDATE checkin_shifts SET gate_id = ?, starts_at = ?, ends_at = ? WHERE id = ?",
                gateId,
                Timestamp.from(startsAt),
                Timestamp.from(endsAt),
                shiftId);
    }

    @Override
    public void deleteShift(UUID shiftId) {
        jdbc.update("DELETE FROM checkin_shifts WHERE id = ?", shiftId);
    }

    @Override
    public Optional<Shift> findShift(UUID organizationId, UUID shiftId) {
        return withStaff(jdbc.query(
                        SHIFT_SELECT + " WHERE s.organization_id = ? AND s.id = ?",
                        JdbcShiftRepository::shift,
                        organizationId,
                        shiftId))
                .stream()
                .findFirst();
    }

    @Override
    public List<Shift> shiftsOfEvent(UUID organizationId, UUID eventId) {
        return withStaff(jdbc.query(
                SHIFT_SELECT + " WHERE s.organization_id = ? AND s.event_id = ? ORDER BY s.starts_at, g.name",
                JdbcShiftRepository::shift,
                organizationId,
                eventId));
    }

    @Override
    public List<Shift> shiftsOfStaff(UUID organizationId, UUID userId, Instant endsAfter) {
        return withStaff(jdbc.query(
                SHIFT_SELECT
                        + """
                         JOIN checkin_shift_staff st ON st.shift_id = s.id
                         WHERE s.organization_id = ? AND st.user_id = ? AND s.ends_at > ?
                         ORDER BY s.starts_at
                        """,
                JdbcShiftRepository::shift,
                organizationId,
                userId,
                Timestamp.from(endsAfter)));
    }

    @Override
    public void replaceStaff(UUID shiftId, Collection<UUID> userIds) {
        jdbc.update("DELETE FROM checkin_shift_staff WHERE shift_id = ?", shiftId);
        for (UUID userId : new java.util.LinkedHashSet<>(userIds)) {
            jdbc.update("INSERT INTO checkin_shift_staff (shift_id, user_id) VALUES (?, ?)", shiftId, userId);
        }
    }

    @Override
    public Optional<ShiftSession> openSession(UUID shiftId, UUID userId) {
        return jdbc
                .query(
                        SESSION_SELECT + " WHERE shift_id = ? AND user_id = ? AND ended_at IS NULL",
                        JdbcShiftRepository::session,
                        shiftId,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ShiftSession> openSessionForSession(UUID userId, UUID eventSessionId) {
        return jdbc
                .query(
                        """
                        SELECT p.id, p.shift_id, p.user_id, p.device_id, p.started_at, p.ended_at
                          FROM checkin_shift_sessions p
                          JOIN checkin_shifts s ON s.id = p.shift_id
                         WHERE p.user_id = ? AND p.ended_at IS NULL AND s.event_session_id = ?
                         ORDER BY p.started_at DESC
                         LIMIT 1
                        """,
                        JdbcShiftRepository::session,
                        userId,
                        eventSessionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ShiftSession> sessionCovering(UUID userId, UUID eventSessionId, Instant at) {
        return jdbc
                .query(
                        """
                        SELECT p.id, p.shift_id, p.user_id, p.device_id, p.started_at, p.ended_at
                          FROM checkin_shift_sessions p
                          JOIN checkin_shifts s ON s.id = p.shift_id
                         WHERE p.user_id = ? AND s.event_session_id = ?
                           AND p.started_at <= ? AND (p.ended_at IS NULL OR p.ended_at >= ?)
                         ORDER BY p.started_at DESC
                         LIMIT 1
                        """,
                        JdbcShiftRepository::session,
                        userId,
                        eventSessionId,
                        Timestamp.from(at),
                        Timestamp.from(at))
                .stream()
                .findFirst();
    }

    @Override
    public ShiftSession startSession(UUID shiftId, UUID userId, String deviceId, Instant at) {
        // ON CONFLICT trên index một-phiên-đang-mở: bấm "Bắt đầu" hai lần (hoặc hai máy cùng lúc) là
        // quay lại phiên cũ, không mở phiên thứ hai.
        jdbc.update(
                """
                INSERT INTO checkin_shift_sessions (id, shift_id, user_id, device_id, started_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (shift_id, user_id) WHERE ended_at IS NULL DO NOTHING
                """,
                UUID.randomUUID(),
                shiftId,
                userId,
                deviceId,
                Timestamp.from(at));
        return openSession(shiftId, userId).orElseThrow();
    }

    @Override
    public Optional<ShiftSession> endSession(UUID shiftId, UUID userId, Instant at) {
        return jdbc
                .query(
                        """
                        UPDATE checkin_shift_sessions SET ended_at = GREATEST(?, started_at)
                         WHERE shift_id = ? AND user_id = ? AND ended_at IS NULL
                        RETURNING id, shift_id, user_id, device_id, started_at, ended_at
                        """,
                        JdbcShiftRepository::session,
                        Timestamp.from(at),
                        shiftId,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ShiftSession> openSessionsOfShifts(Collection<UUID> shiftIds) {
        if (shiftIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                SESSION_SELECT + " WHERE ended_at IS NULL AND shift_id = ANY (?) ORDER BY started_at",
                JdbcShiftRepository::session,
                (Object) shiftIds.toArray(UUID[]::new));
    }

    /** Gắn danh sách nhân viên vào các ca bằng MỘT câu truy vấn, không phải một câu mỗi ca. */
    private List<Shift> withStaff(List<Shift> shifts) {
        if (shifts.isEmpty()) {
            return shifts;
        }
        Map<UUID, List<UUID>> staff = new LinkedHashMap<>();
        jdbc.query(
                "SELECT shift_id, user_id FROM checkin_shift_staff WHERE shift_id = ANY (?) ORDER BY user_id",
                rs -> {
                    staff.computeIfAbsent(rs.getObject("shift_id", UUID.class), key -> new ArrayList<>())
                            .add(rs.getObject("user_id", UUID.class));
                },
                (Object) shifts.stream().map(Shift::id).toArray(UUID[]::new));
        return shifts.stream()
                .map(s -> new Shift(
                        s.id(),
                        s.organizationId(),
                        s.eventId(),
                        s.eventSessionId(),
                        s.gateId(),
                        s.gateName(),
                        s.startsAt(),
                        s.endsAt(),
                        List.copyOf(staff.getOrDefault(s.id(), List.of()))))
                .toList();
    }

    private static Gate gate(ResultSet rs, int rowNum) throws SQLException {
        return new Gate(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Shift shift(ResultSet rs, int rowNum) throws SQLException {
        return new Shift(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("event_session_id", UUID.class),
                rs.getObject("gate_id", UUID.class),
                rs.getString("gate_name"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                List.of());
    }

    private static ShiftSession session(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        return new ShiftSession(
                rs.getObject("id", UUID.class),
                rs.getObject("shift_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("device_id"),
                rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant());
    }
}
