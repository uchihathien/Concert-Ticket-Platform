// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogQueries.EventFilter;
import com.nexaticket.catalog.application.query.CatalogViews;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
     * <h3>Vì sao có CTE</h3>
     *
     * <p>Bộ lọc thời gian và giá so sánh với <b>giá trị dẫn xuất</b> — suất kế tiếp và giá thấp
     * nhất — mà hai thứ đó là subquery, không phải cột. SQL không cho tham chiếu bí danh của
     * SELECT trong chính mệnh đề WHERE của nó, nên hoặc viết lại cả subquery lần thứ hai trong
     * WHERE, hoặc tính một lần trong CTE rồi lọc bên ngoài. Cách đầu là hai bản sao của cùng một
     * phép tính, và bản sai sẽ là bản người dùng nhìn thấy.
     *
     * <p>Ba bộ lọc chữ nằm <b>trong</b> CTE chứ không ngoài: chúng cắt bớt số dòng trước khi hai
     * subquery kia phải chạy cho từng dòng còn lại.
     *
     * <p>Mọi bộ lọc đều theo kiểu "null thì bỏ qua", viết thẳng vào SQL bằng {@code ? IS NULL OR …}
     * thay vì nối chuỗi điều kiện. Nối chuỗi là chỗ mà một ngày nào đó có người nối vào giá trị của
     * người dùng.
     *
     * <p>{@code CAST(? AS text)} là BẮT BUỘC, không phải để cho đẹp. PostgreSQL suy kiểu tham số từ
     * ngữ cảnh, mà {@code ? IS NULL} không cho ngữ cảnh nào — server trả về
     * {@code could not determine data type of parameter}. Lỗi chỉ xuất hiện khi thật sự truyền
     * null, tức là đúng lúc người dùng KHÔNG lọc gì cả: trang danh sách mặc định.
     */
    @Override
    public List<CatalogViews.EventCard> publishedEvents(EventFilter filter, int limit, int offset) {
        return jdbc.query(
                CARDS_CTE + "SELECT * FROM cards" + DERIVED_FILTERS + " ORDER BY published_at DESC LIMIT ? OFFSET ?",
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
                concat(filterParams(filter), new Object[] {limit, offset}));
    }

    /**
     * Đếm đúng tập dòng mà {@link #publishedEvents} trả về.
     *
     * <p>Dùng chung {@link #CARDS_CTE}, {@link #DERIVED_FILTERS} và {@link #filterParams} với câu
     * lấy trang — không phải để gọn, mà vì hai câu lọc khác nhau nghĩa là tổng số trang không khớp
     * số dòng thật, và người dùng chỉ phát hiện điều đó ở trang cuối.
     *
     * <p>Đắt hơn bản cũ: hai subquery tương quan vẫn chạy cho mọi dòng khớp bộ lọc chữ, kể cả khi
     * không lọc theo thời gian hay giá. Chấp nhận được vì tập ấy là "sự kiện đang bán" — hàng chục
     * đến hàng trăm, không phải hàng triệu — và câu này đứng sau cache 2 phút của endpoint.
     */
    @Override
    public int countPublishedEvents(EventFilter filter) {
        Integer count = jdbc.queryForObject(
                CARDS_CTE + "SELECT count(*) FROM cards" + DERIVED_FILTERS, Integer.class, filterParams(filter));
        return count == null ? 0 : count;
    }

    /**
     * Thẻ sự kiện đã tính xong phần dẫn xuất, chưa lọc theo thời gian và giá.
     *
     * <p>{@code published_at} có mặt chỉ để {@code ORDER BY} bên ngoài dùng được — nó không lọt ra
     * {@code EventCard}.
     */
    private static final String CARDS_CTE =
            """
            WITH cards AS (
              SELECT e.slug, e.title, e.summary, e.category, e.poster_url, e.published_at,
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
            )
            """;

    /**
     * Lọc theo suất kế tiếp và giá thấp nhất.
     *
     * <p>{@code IS NOT NULL} trong mỗi mệnh đề là bắt buộc chứ không thừa: sự kiện chưa có suất nào
     * ở tương lai có {@code next_session_at = NULL}, và trong SQL thì {@code NULL >= ?} cho ra NULL
     * chứ không phải false — WHERE loại nó y như false, nhưng người đọc sau không nên phải dựa vào
     * chi tiết ấy. Viết rõ cũng để nó khớp đúng luật cũ ở frontend: không biết ngày thì không thể
     * nói sự kiện diễn ra hôm nay.
     *
     * <p>Cả hai cận trên đều <b>không</b> lấy mốc ({@code <}): hai lựa chọn liền nhau phải rời
     * nhau, nếu không một sự kiện giá đúng 500.000đ sẽ hiện ở cả "dưới 500k" lẫn "500k–1tr".
     */
    private static final String DERIVED_FILTERS =
            """
             WHERE (CAST(? AS timestamptz) IS NULL
                    OR (next_session_at IS NOT NULL AND next_session_at >= CAST(? AS timestamptz)))
               AND (CAST(? AS timestamptz) IS NULL
                    OR (next_session_at IS NOT NULL AND next_session_at <  CAST(? AS timestamptz)))
               AND (CAST(? AS bigint) IS NULL
                    OR (from_price_vnd IS NOT NULL AND from_price_vnd >= CAST(? AS bigint)))
               AND (CAST(? AS bigint) IS NULL
                    OR (from_price_vnd IS NOT NULL AND from_price_vnd <  CAST(? AS bigint)))
            """;

    /**
     * Tham số của {@link #CARDS_CTE} + {@link #DERIVED_FILTERS}, đúng thứ tự, dựng ở <b>một</b> chỗ.
     *
     * <p>Mười bốn tham số vị trí là chỗ dễ sai nhất trong file này, và sai kiểu ấy không gây lỗi —
     * nó chỉ lặng lẽ lọc theo thành phố bằng giá trị của phân loại. Một hàm duy nhất dựng chúng là
     * cách để câu lấy trang và câu đếm không thể lệch nhau.
     */
    private static Object[] filterParams(EventFilter filter) {
        Timestamp from = filter.from() == null ? null : Timestamp.from(filter.from());
        Timestamp to = filter.to() == null ? null : Timestamp.from(filter.to());

        return new Object[] {
            blankToNull(filter.query()),
            blankToNull(filter.query()),
            blankToNull(filter.city()),
            blankToNull(filter.city()),
            blankToNull(filter.category()),
            blankToNull(filter.category()),
            from,
            from,
            to,
            to,
            filter.minPriceVnd(),
            filter.minPriceVnd(),
            filter.maxPriceVnd(),
            filter.maxPriceVnd()
        };
    }

    private static Object[] concat(Object[] head, Object[] tail) {
        Object[] all = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
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
