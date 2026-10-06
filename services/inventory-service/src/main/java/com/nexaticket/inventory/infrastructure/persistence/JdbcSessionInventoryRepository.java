// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.persistence;

import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSessionInventoryRepository implements SessionInventoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcSessionInventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SessionInventory> findBySessionId(UUID eventSessionId) {
        List<SessionInventory> found = jdbc.query(
                """
                SELECT id, event_session_id, event_id, organization_id,
                       sales_open_at, sales_close_at,
                       max_seated_per_hold, max_standing_per_hold,
                       max_units_per_hold, max_tickets_per_customer,
                       hold_ttl_seconds
                  FROM session_inventory
                 WHERE event_session_id = ?
                """,
                (rs, i) -> new SessionInventory(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_session_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getTimestamp("sales_open_at").toInstant(),
                        rs.getTimestamp("sales_close_at").toInstant(),
                        new PurchaseLimits(
                                rs.getInt("max_seated_per_hold"),
                                rs.getInt("max_standing_per_hold"),
                                rs.getInt("max_units_per_hold"),
                                rs.getInt("max_tickets_per_customer")),
                        Duration.ofSeconds(rs.getInt("hold_ttl_seconds"))),
                eventSessionId);
        return found.stream().findFirst();
    }

    @Override
    public long currentAvailabilityVersion(UUID eventSessionId) {
        Long version = jdbc
                .query(
                        "SELECT availability_version FROM session_inventory WHERE event_session_id = ?",
                        (rs, i) -> rs.getLong("availability_version"),
                        eventSessionId)
                .stream()
                .findFirst()
                .orElse(null);
        return version == null ? 0L : version;
    }

    @Override
    public long bumpAvailabilityVersion(UUID eventSessionId) {
        Long version = jdbc
                .query(
                        """
                        UPDATE session_inventory
                           SET availability_version = nextval('availability_version_seq')
                         WHERE event_session_id = ?
                        RETURNING availability_version
                        """,
                        (rs, i) -> rs.getLong("availability_version"),
                        eventSessionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Không có tồn kho cho suất " + eventSessionId));
        return version;
    }
}
