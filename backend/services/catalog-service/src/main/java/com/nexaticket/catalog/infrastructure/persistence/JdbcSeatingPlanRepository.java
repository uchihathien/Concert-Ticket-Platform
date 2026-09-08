// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.AdmissionType;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.SeatOverride;
import com.nexaticket.catalog.domain.model.SeatingPlan;
import com.nexaticket.catalog.domain.model.Zone;
import com.nexaticket.catalog.domain.model.ZoneKind;
import com.nexaticket.catalog.domain.model.ZoneUsage;
import com.nexaticket.catalog.domain.port.SeatingPlanRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSeatingPlanRepository implements SeatingPlanRepository {

    private final JdbcTemplate jdbc;

    public JdbcSeatingPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SeatingPlan> load(UUID eventSessionId) {
        Optional<SessionTiming> timing = timingOf(eventSessionId);
        if (timing.isEmpty()) {
            return Optional.empty();
        }

        List<Zone> zones = jdbc
                .query(
                        """
                        SELECT z.id, z.zone_code, z.name, z.kind, z.admission_type, z.standing_capacity
                          FROM venue_zones z
                          JOIN event_sessions s ON s.layout_version_id = z.layout_version_id
                         WHERE s.id = ?
                         ORDER BY z.display_order, z.zone_code
                        """,
                        (rs, i) -> new Object[] {
                            rs.getObject("id", UUID.class),
                            rs.getString("zone_code"),
                            rs.getString("name"),
                            ZoneKind.valueOf(rs.getString("kind")),
                            AdmissionType.valueOf(rs.getString("admission_type")),
                            rs.getObject("standing_capacity", Integer.class)
                        },
                        eventSessionId)
                .stream()
                .map(row -> new Zone(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        (ZoneKind) row[3],
                        (AdmissionType) row[4],
                        (Integer) row[5],
                        fixedSeatsOf((UUID) row[0])))
                .toList();

        List<ZoneUsage> usages = jdbc.query(
                """
                SELECT zone_id, included, ticket_tier_id, standing_capacity
                  FROM seating_plan_zone_usages WHERE event_session_id = ?
                """,
                (rs, i) -> new ZoneUsage(
                        rs.getObject("zone_id", UUID.class),
                        rs.getBoolean("included"),
                        rs.getObject("ticket_tier_id", UUID.class),
                        rs.getObject("standing_capacity", Integer.class)),
                eventSessionId);

        List<SeatOverride> overrides = jdbc.query(
                """
                SELECT zone_id, seat_code, action, ticket_tier_id
                  FROM seating_plan_seat_overrides WHERE event_session_id = ?
                """,
                (rs, i) -> new SeatOverride(
                        rs.getObject("zone_id", UUID.class),
                        rs.getString("seat_code"),
                        SeatOverride.Action.valueOf(rs.getString("action")),
                        rs.getObject("ticket_tier_id", UUID.class)),
                eventSessionId);

        List<SeatingPlan.TicketTier> tiers = jdbc.query(
                "SELECT id, name, price_vnd FROM ticket_tiers WHERE event_session_id = ? ORDER BY display_order",
                (rs, i) -> new SeatingPlan.TicketTier(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price_vnd")),
                eventSessionId);

        return Optional.of(new SeatingPlan(
                eventSessionId,
                timing.get().eventId(),
                timing.get().organizationId(),
                zones,
                usages,
                overrides,
                tiers));
    }

    private List<Zone.FixedSeat> fixedSeatsOf(UUID zoneId) {
        return jdbc.query(
                """
                SELECT id, seat_code, row_label, seat_label, pos_x, pos_y
                  FROM venue_fixed_seats WHERE zone_id = ? ORDER BY row_label, seat_label
                """,
                (rs, i) -> new Zone.FixedSeat(
                        rs.getObject("id", UUID.class),
                        rs.getString("seat_code"),
                        rs.getString("row_label"),
                        rs.getString("seat_label"),
                        rs.getBigDecimal("pos_x"),
                        rs.getBigDecimal("pos_y")),
                zoneId);
    }

    @Override
    public Optional<SessionTiming> timingOf(UUID eventSessionId) {
        return jdbc
                .query(
                        """
                        SELECT s.event_id, e.organization_id, s.starts_at, s.sales_open_at, s.sales_close_at
                          FROM event_sessions s JOIN events e ON e.id = s.event_id
                         WHERE s.id = ?
                        """,
                        (rs, i) -> new SessionTiming(
                                rs.getObject("event_id", UUID.class),
                                rs.getObject("organization_id", UUID.class),
                                rs.getTimestamp("starts_at").toInstant(),
                                rs.getTimestamp("sales_open_at").toInstant(),
                                rs.getTimestamp("sales_close_at").toInstant()),
                        eventSessionId)
                .stream()
                .findFirst();
    }

    @Override
    public PurchaseLimits sessionLimits(UUID eventSessionId) {
        return jdbc
                .query(
                        """
                        SELECT max_seated_per_hold, max_standing_per_hold,
                               max_units_per_hold, max_tickets_per_customer
                          FROM event_sessions WHERE id = ?
                        """,
                        (rs, i) -> new PurchaseLimits(
                                rs.getObject("max_seated_per_hold", Integer.class),
                                rs.getObject("max_standing_per_hold", Integer.class),
                                rs.getObject("max_units_per_hold", Integer.class),
                                rs.getObject("max_tickets_per_customer", Integer.class)),
                        eventSessionId)
                .stream()
                .findFirst()
                .orElse(PurchaseLimits.INHERIT_ALL);
    }

    @Override
    public void markMaterialized(UUID eventSessionId, Instant at) {
        jdbc.update("UPDATE event_sessions SET materialized_at = ? WHERE id = ?", Timestamp.from(at), eventSessionId);
    }
}
