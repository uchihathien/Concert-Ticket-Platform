// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
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
    public void record(
            UUID ticketId, UUID eventSessionId, UUID staffId, String deviceId, CheckinResult result, String note) {
        jdbc.update(
                """
                INSERT INTO checkin_log (id, ticket_id, event_session_id, scanned_by, device_id, result, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ticketId,
                eventSessionId,
                staffId,
                deviceId,
                result.name(),
                note);
    }
}
