// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
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

/** Đường đọc bằng SQL. */
@Repository
public class JdbcCatalogQueries implements CatalogQueries {

    private final JdbcTemplate jdbc;

    public JdbcCatalogQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean sessionExists(UUID eventSessionId) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM event_sessions WHERE id = ?", Integer.class, eventSessionId);
        return count != null && count > 0;
    }

    /**
     * Danh sách sự kiện đang bán.
     *
     * <p>Giá thấp nhất và suất kế tiếp tính bằng subquery tương quan chứ không bằng join rồi
     * GROUP BY: chúng lọc theo những điều kiện khác nhau (suất kế tiếp phải còn ở tương lai, giá
     * thấp nhất thì không), nên gộp vào một phép gom nhóm sẽ ra sai một trong hai.
     *
     * <p>Ba bộ lọc đều theo kiểu "null thì bỏ qua", viết thẳng vào SQL bằng {@code ? IS NULL OR …}
     * thay vì nối chuỗi điều kiện. Nối chuỗi là chỗ mà một ngày nào đó có người nối vào giá trị của
     * người dùng.
     *
     * <p>{@code CAST(? AS text)} là BẮT BUỘC, không phải để cho đẹp. PostgreSQL suy kiểu tham số từ
     * ngữ cảnh, mà {@code ? IS NULL} không cho ngữ cảnh nào — server trả về
     * {@code could not determine data type of parameter}. Lỗi chỉ xuất hiện khi thật sự truyền
     * null, tức là đúng lúc người dùng KHÔNG lọc gì cả: trang danh sách mặc định.
     */
    @Override
    public List<CatalogViews.EventCard> publishedEvents(
            String query, String city, String category, int limit, int offset) {
        return jdbc.query(
                """
                SELECT e.slug, e.title, e.summary, e.category, e.poster_url,
                       v.city, v.name AS venue_name,
                       (SELECT min(s.starts_at) FROM event_sessions s
                         WHERE s.event_id = e.id AND s.starts_at > now())        AS next_session_at,
                       (SELECT min(t.price_vnd) FROM ticket_types t
                          JOIN event_sessions s2 ON s2.id = t.event_session_id
                         WHERE s2.event_id = e.id)                               AS from_price_vnd,
                       (SELECT count(*) FROM event_sessions s3 WHERE s3.event_id = e.id) AS session_count
                  FROM events e
                  JOIN venues v ON v.id = e.venue_id
                 WHERE e.status = 'PUBLISHED'
                   AND (CAST(? AS text) IS NULL OR e.title ILIKE '%' || CAST(? AS text) || '%')
                   AND (CAST(? AS text) IS NULL OR v.city    = CAST(? AS text))
                   AND (CAST(? AS text) IS NULL OR e.category = CAST(? AS text))
                 ORDER BY e.published_at DESC
                 LIMIT ? OFFSET ?
                """,
                (rs, i) -> new CatalogViews.EventCard(
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("category"),
                        rs.getString("poster_url"),
                        rs.getString("city"),
                        rs.getString("venue_name"),
                        instant(rs, "next_session_at"),
                        nullableLong(rs, "from_price_vnd"),
                        rs.getInt("session_count")),
                blankToNull(query),
                blankToNull(query),
                blankToNull(city),
                blankToNull(city),
                blankToNull(category),
                blankToNull(category),
                limit,
                offset);
    }

    @Override
    public int countPublishedEvents(String query, String city, String category) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM events e
                  JOIN venues v ON v.id = e.venue_id
                 WHERE e.status = 'PUBLISHED'
                   AND (CAST(? AS text) IS NULL OR e.title ILIKE '%' || CAST(? AS text) || '%')
                   AND (CAST(? AS text) IS NULL OR v.city    = CAST(? AS text))
                   AND (CAST(? AS text) IS NULL OR e.category = CAST(? AS text))
                """,
                Integer.class,
                blankToNull(query),
                blankToNull(query),
                blankToNull(city),
                blankToNull(city),
                blankToNull(category),
                blankToNull(category));
        return count == null ? 0 : count;
    }

    /**
     * Chi tiết một sự kiện công khai.
     *
     * <p>{@code status = 'PUBLISHED'} nằm trong WHERE chứ không được kiểm ở tầng trên: đây là ranh
     * giới giữa "bản nháp của ban tổ chức" và "thứ cả internet đọc được". Để nó ở tầng trên là để
     * một lần refactor quên mất mà lộ toàn bộ sự kiện chưa công bố.
     */
    @Override
    public Optional<CatalogViews.EventDetail> publishedEventBySlug(String slug) {
        List<EventHeader> headers = jdbc.query(
                """
                SELECT e.id, e.slug, e.title, e.summary, e.description, e.category, e.poster_url,
                       v.city, v.name AS venue_name, v.address AS venue_address
                  FROM events e
                  JOIN venues v ON v.id = e.venue_id
                 WHERE e.status = 'PUBLISHED' AND e.slug = ?
                """,
                (rs, i) -> new EventHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("description"),
                        rs.getString("category"),
                        rs.getString("poster_url"),
                        rs.getString("city"),
                        rs.getString("venue_name"),
                        rs.getString("venue_address")),
                slug);

        if (headers.isEmpty()) {
            return Optional.empty();
        }
        EventHeader header = headers.get(0);
        return Optional.of(new CatalogViews.EventDetail(
                header.slug(),
                header.title(),
                header.summary(),
                header.description(),
                header.category(),
                header.posterUrl(),
                header.city(),
                header.venueName(),
                header.venueAddress(),
                sessionsOf(header.id())));
    }

    @Override
    public List<String> citiesWithPublishedEvents() {
        return jdbc.queryForList(
                """
                SELECT DISTINCT v.city
                  FROM events e
                  JOIN venues v ON v.id = e.venue_id
                 WHERE e.status = 'PUBLISHED'
                 ORDER BY v.city
                """,
                String.class);
    }

    @Override
    public List<CatalogViews.AdminEventRow> organizationEvents(UUID organizationId) {
        return jdbc.query(
                """
                SELECT e.id, e.slug, e.title, e.category, e.status, e.published_at,
                       v.name AS venue_name,
                       (SELECT min(s.starts_at) FROM event_sessions s
                         WHERE s.event_id = e.id AND s.starts_at > now())        AS next_session_at,
                       (SELECT count(*) FROM event_sessions s2 WHERE s2.event_id = e.id) AS session_count,
                       (SELECT count(*) FROM ticket_types t
                          JOIN event_sessions s3 ON s3.id = t.event_session_id
                         WHERE s3.event_id = e.id)                               AS ticket_type_count,
                       (SELECT coalesce(sum(coalesce(z.capacity, z.row_count * z.seats_per_row)), 0)
                          FROM venue_zones z WHERE z.venue_id = v.id)            AS capacity
                  FROM events e
                  JOIN venues v ON v.id = e.venue_id
                 WHERE e.organization_id = ?
                 ORDER BY e.created_at DESC
                """,
                (rs, i) -> new CatalogViews.AdminEventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("category"),
                        rs.getString("status"),
                        instant(rs, "published_at"),
                        rs.getString("venue_name"),
                        instant(rs, "next_session_at"),
                        rs.getInt("session_count"),
                        rs.getInt("ticket_type_count"),
                        rs.getInt("capacity")),
                organizationId);
    }

    /** Suất diễn kèm hạng vé của một sự kiện công khai. */
    private List<CatalogViews.PublicSession> sessionsOf(UUID eventId) {
        Map<UUID, List<CatalogViews.PublicTier>> tiers = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT t.id, t.event_session_id, t.name, t.price_vnd,
                       z.zone_code, z.name AS zone_name,
                       coalesce(z.capacity, z.row_count * z.seats_per_row) AS capacity
                  FROM ticket_types t
                  JOIN event_sessions s ON s.id = t.event_session_id
                  JOIN venue_zones z    ON z.id = t.venue_zone_id
                 WHERE s.event_id = ?
                 ORDER BY t.sort_order, t.price_vnd
                """,
                rs -> {
                    UUID sessionId = rs.getObject("event_session_id", UUID.class);
                    tiers.computeIfAbsent(sessionId, key -> new ArrayList<>())
                            .add(new CatalogViews.PublicTier(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("name"),
                                    rs.getLong("price_vnd"),
                                    rs.getString("zone_code"),
                                    rs.getString("zone_name"),
                                    rs.getInt("capacity")));
                },
                eventId);

        return jdbc.query(
                """
                SELECT id, starts_at, ends_at, sales_open_at, sales_close_at
                  FROM event_sessions
                 WHERE event_id = ?
                 ORDER BY starts_at
                """,
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new CatalogViews.PublicSession(
                            id,
                            instant(rs, "starts_at"),
                            instant(rs, "ends_at"),
                            instant(rs, "sales_open_at"),
                            instant(rs, "sales_close_at"),
                            tiers.getOrDefault(id, List.of()));
                },
                eventId);
    }

    private record EventHeader(
            UUID id,
            String slug,
            String title,
            String summary,
            String description,
            String category,
            String posterUrl,
            String city,
            String venueName,
            String venueAddress) {}

    /** Chuỗi rỗng từ query string phải thành null, nếu không bộ lọc "để trống" sẽ lọc mất hết. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
