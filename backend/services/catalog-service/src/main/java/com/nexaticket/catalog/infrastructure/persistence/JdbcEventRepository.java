// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.port.EventRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter persistence cho sự kiện.
 *
 * <p>Đọc cả aggregate bằng <b>hai</b> câu truy vấn (suất diễn, hạng vé) rồi ghép trong bộ nhớ, chứ
 * không phải một câu join ba bảng: join sẽ nhân bản hàng suất diễn theo số hạng vé, và số hàng
 * phải xử lý tăng theo tích chứ không theo tổng. Với sự kiện mười suất mỗi suất năm hạng thì đó là
 * 50 hàng thay vì 15 — chưa nguy hiểm, nhưng cấu trúc thì sai từ đầu và sẽ tệ dần.
 */
@Repository
public class JdbcEventRepository implements EventRepository {

    private final JdbcTemplate jdbc;

    public JdbcEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Event event) {
        jdbc.update(
                """
                INSERT INTO events (id, organization_id, venue_id, slug, title, summary,
                                    description, category, poster_url, status, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.organizationId(),
                event.venueId(),
                event.slug().value(),
                event.title(),
                event.summary(),
                event.description(),
                event.category(),
                event.posterUrl(),
                event.status().name(),
                event.publishedAt() == null ? null : Timestamp.from(event.publishedAt()));
    }

    @Override
    public void update(Event event) {
        jdbc.update(
                """
                UPDATE events
                   SET title = ?, summary = ?, description = ?, category = ?, poster_url = ?,
                       status = ?, published_at = ?, updated_at = now()
                 WHERE id = ?
                """,
                event.title(),
                event.summary(),
                event.description(),
                event.category(),
                event.posterUrl(),
                event.status().name(),
                event.publishedAt() == null ? null : Timestamp.from(event.publishedAt()),
                event.id());
    }

    @Override
    public void addSession(EventSession session) {
        jdbc.update(
                """
                INSERT INTO event_sessions (id, event_id, starts_at, ends_at, sales_open_at, sales_close_at,
                                            max_seated_per_hold, max_standing_per_hold,
                                            max_units_per_hold, max_tickets_per_customer)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.id(),
                session.eventId(),
                Timestamp.from(session.startsAt()),
                session.endsAt() == null ? null : Timestamp.from(session.endsAt()),
                Timestamp.from(session.salesOpenAt()),
                Timestamp.from(session.salesCloseAt()),
                session.maxSeatedPerHold(),
                session.maxStandingPerHold(),
                session.maxUnitsPerHold(),
                session.maxTicketsPerCustomer());
    }

    @Override
    public void addTicketType(TicketType type) {
        jdbc.update(
                """
                INSERT INTO ticket_types (id, event_session_id, venue_zone_id, name, price_vnd, sort_order)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                type.id(),
                type.eventSessionId(),
                type.venueZoneId(),
                type.name(),
                type.priceVnd(),
                type.sortOrder());
    }

    @Override
    public boolean slugExists(Slug slug) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM events WHERE slug = ?", Integer.class, slug.value());
        return count != null && count > 0;
    }

    @Override
    public Optional<Event> findByIdForOrganization(UUID organizationId, UUID eventId) {
        List<Event> found = jdbc.query(
                """
                SELECT id, organization_id, venue_id, slug, title, summary, description,
                       category, poster_url, status, published_at
                  FROM events
                 WHERE organization_id = ? AND id = ?
                """,
                (rs, i) -> shell(rs),
                organizationId,
                eventId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        Event shell = found.get(0);
        loadSessions(shell).forEach(shell::addSession);
        return Optional.of(shell);
    }

    @Override
    public Optional<UUID> organizationOfSession(UUID sessionId) {
        return jdbc
                .query(
                        """
                        SELECT e.organization_id
                          FROM event_sessions s
                          JOIN events e ON e.id = s.event_id
                         WHERE s.id = ?
                        """,
                        (rs, i) -> rs.getObject("organization_id", UUID.class),
                        sessionId)
                .stream()
                .findFirst();
    }

    /** Sự kiện chưa gắn suất diễn — dựng xong mới nạp thêm. */
    private static Event shell(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new Event(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("venue_id", UUID.class),
                new Slug(rs.getString("slug")),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getString("poster_url"),
                EventStatus.valueOf(rs.getString("status")),
                publishedAt == null ? null : publishedAt.toInstant(),
                List.of());
    }

    private List<EventSession> loadSessions(Event event) {
        Map<UUID, List<TicketType>> typesBySession = loadTicketTypes(event.id());

        return jdbc.query(
                """
                SELECT id, event_id, starts_at, ends_at, sales_open_at, sales_close_at,
                       max_seated_per_hold, max_standing_per_hold,
                       max_units_per_hold, max_tickets_per_customer
                  FROM event_sessions
                 WHERE event_id = ?
                 ORDER BY starts_at
                """,
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new EventSession(
                            id,
                            rs.getObject("event_id", UUID.class),
                            instant(rs, "starts_at"),
                            instant(rs, "ends_at"),
                            instant(rs, "sales_open_at"),
                            instant(rs, "sales_close_at"),
                            nullableInt(rs, "max_seated_per_hold"),
                            nullableInt(rs, "max_standing_per_hold"),
                            nullableInt(rs, "max_units_per_hold"),
                            nullableInt(rs, "max_tickets_per_customer"),
                            typesBySession.getOrDefault(id, List.of()));
                },
                event.id());
    }

    private Map<UUID, List<TicketType>> loadTicketTypes(UUID eventId) {
        Map<UUID, List<TicketType>> bySession = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT t.id, t.event_session_id, t.venue_zone_id, t.name, t.price_vnd, t.sort_order
                  FROM ticket_types t
                  JOIN event_sessions s ON s.id = t.event_session_id
                 WHERE s.event_id = ?
                 ORDER BY t.sort_order, t.price_vnd
                """,
                rs -> {
                    UUID sessionId = rs.getObject("event_session_id", UUID.class);
                    bySession
                            .computeIfAbsent(sessionId, key -> new ArrayList<>())
                            .add(new TicketType(
                                    rs.getObject("id", UUID.class),
                                    sessionId,
                                    rs.getObject("venue_zone_id", UUID.class),
                                    rs.getString("name"),
                                    rs.getLong("price_vnd"),
                                    rs.getInt("sort_order")));
                },
                eventId);
        return bySession;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** {@code getInt} trả 0 cho NULL; ở đây NULL nghĩa là "theo mặc định nền tảng", 0 thì vô nghĩa. */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
