// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.persistence;

import com.nexaticket.ordering.application.query.SalesStats;
import com.nexaticket.ordering.application.query.SalesStatsReader;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Số liệu tổng hợp đọc thẳng từ {@code orders} và {@code order_items}.
 *
 * <p>Mọi truy vấn bắt đầu bằng {@code organization_id = ? AND created_at} trong một khoảng — đúng
 * hai cột của {@code idx_order_organization}, nên database chỉ đi qua đơn của một tổ chức trong một
 * kỳ, rồi mới nối sang dòng vé bằng {@code idx_item_order}.
 *
 * <p>CTE {@code base} gom mỗi đơn về MỘT dòng (số vé và số tiền của những dòng vé khớp bộ lọc).
 * Đếm đơn trên bảng đã nối với dòng vé sẽ đếm một đơn ba vé thành ba đơn.
 */
@Repository
public class JdbcSalesStatsReader implements SalesStatsReader {

    private static final String LOCAL = "(created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSalesStatsReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SalesStats.Totals totals(SalesStats.Filter filter) {
        String sql = base(filter)
                + """
                SELECT COUNT(*)                                             AS orders_total,
                       COUNT(*) FILTER (WHERE status = 'PAID')              AS orders_paid,
                       COUNT(*) FILTER (WHERE status = 'AWAITING_PAYMENT')  AS orders_awaiting,
                       COUNT(*) FILTER (WHERE status = 'EXPIRED')           AS orders_expired,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED')         AS orders_cancelled,
                       COALESCE(SUM(amount)  FILTER (WHERE status = 'PAID'), 0) AS gross_vnd,
                       COALESCE(SUM(tickets) FILTER (WHERE status = 'PAID'), 0) AS tickets_sold,
                       AVG(EXTRACT(EPOCH FROM (paid_at - created_at)))
                           FILTER (WHERE status = 'PAID')                   AS avg_seconds_to_pay,
                       COUNT(DISTINCT user_id) FILTER (WHERE status = 'PAID') AS buyers
                  FROM base
                """;
        return jdbc.queryForObject(sql, params(filter), (rs, i) -> {
            long total = rs.getLong("orders_total");
            long paid = rs.getLong("orders_paid");
            long awaiting = rs.getLong("orders_awaiting");
            long expired = rs.getLong("orders_expired");
            long cancelled = rs.getLong("orders_cancelled");
            double seconds = rs.getDouble("avg_seconds_to_pay");
            // `wasNull` nói về cột VỪA đọc — phải hỏi ngay, trước khi đọc cột nào khác.
            boolean noPaidOrder = rs.wasNull();
            return new SalesStats.Totals(
                    total,
                    paid,
                    awaiting,
                    expired,
                    cancelled,
                    total - paid - awaiting - expired - cancelled,
                    rs.getLong("gross_vnd"),
                    rs.getLong("tickets_sold"),
                    noPaidOrder ? null : seconds,
                    rs.getLong("buyers"));
        });
    }

    @Override
    public List<SalesStats.Point> series(SalesStats.Filter filter, String unit) {
        // `unit` đã được SalesStatsQuery kiểm là một trong ba hằng; kiểm lại ở đây vì nó được ghép
        // thẳng vào câu SQL, và lớp này không nên tin người gọi tiếp theo cũng cẩn thận như vậy.
        if (!List.of("hour", "day", "week").contains(unit)) {
            throw new IllegalArgumentException("Đơn vị gom không hợp lệ: " + unit);
        }
        String sql = base(filter)
                + """
                SELECT date_trunc('%s', %s)                                 AS bucket,
                       COALESCE(SUM(amount)  FILTER (WHERE status = 'PAID'), 0) AS gross_vnd,
                       COALESCE(SUM(tickets) FILTER (WHERE status = 'PAID'), 0) AS tickets_sold,
                       COUNT(*)                                             AS orders_created,
                       COUNT(*) FILTER (WHERE status = 'PAID')              AS orders_paid,
                       COUNT(*) FILTER (WHERE status = 'EXPIRED')           AS orders_expired,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED')         AS orders_cancelled
                  FROM base
                 GROUP BY 1
                 ORDER BY 1
                """
                        .formatted(unit, LOCAL);
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new SalesStats.Point(
                        rs.getTimestamp("bucket").toLocalDateTime(),
                        rs.getLong("gross_vnd"),
                        rs.getLong("tickets_sold"),
                        rs.getLong("orders_created"),
                        rs.getLong("orders_paid"),
                        rs.getLong("orders_expired"),
                        rs.getLong("orders_cancelled")));
    }

    @Override
    public List<SalesStats.SessionRow> bySession(SalesStats.Filter filter) {
        String sql = base(filter)
                + """
                SELECT event_id, event_session_id,
                       COALESCE(SUM(tickets) FILTER (WHERE status = 'PAID'), 0) AS tickets_sold,
                       COALESCE(SUM(amount)  FILTER (WHERE status = 'PAID'), 0) AS gross_vnd,
                       COUNT(*)                                             AS orders_total,
                       COUNT(*) FILTER (WHERE status = 'PAID')              AS orders_paid,
                       COUNT(*) FILTER (WHERE status = 'AWAITING_PAYMENT')  AS orders_awaiting,
                       COUNT(*) FILTER (WHERE status = 'EXPIRED')           AS orders_expired,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED')         AS orders_cancelled
                  FROM base
                 GROUP BY event_id, event_session_id
                 ORDER BY gross_vnd DESC, event_session_id
                """;
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new SalesStats.SessionRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("event_session_id", UUID.class),
                        rs.getLong("tickets_sold"),
                        rs.getLong("gross_vnd"),
                        rs.getLong("orders_total"),
                        rs.getLong("orders_paid"),
                        rs.getLong("orders_awaiting"),
                        rs.getLong("orders_expired"),
                        rs.getLong("orders_cancelled")));
    }

    @Override
    public List<SalesStats.TicketTypeRow> byTicketType(SalesStats.Filter filter) {
        String sql =
                """
                SELECT o.event_id, o.event_session_id, i.ticket_type_id,
                       MAX(i.ticket_type_name)                AS ticket_type_name,
                       MAX(i.unit_price_vnd)                  AS unit_price_vnd,
                       COUNT(*)                               AS tickets_sold,
                       SUM(i.unit_price_vnd - i.discount_vnd) AS gross_vnd
                  FROM orders o
                  JOIN order_items i ON i.order_id = o.id
                 WHERE o.status = 'PAID' AND %s
                 GROUP BY o.event_id, o.event_session_id, i.ticket_type_id
                 ORDER BY gross_vnd DESC, i.ticket_type_id
                """
                        .formatted(where(filter));
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new SalesStats.TicketTypeRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("event_session_id", UUID.class),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        rs.getLong("unit_price_vnd"),
                        rs.getLong("tickets_sold"),
                        rs.getLong("gross_vnd")));
    }

    @Override
    public List<SalesStats.HeatCell> heatmap(SalesStats.Filter filter) {
        String sql = base(filter)
                + """
                SELECT EXTRACT(ISODOW FROM %1$s)::int AS day_of_week,
                       EXTRACT(HOUR   FROM %1$s)::int AS hour,
                       SUM(amount)                    AS gross_vnd,
                       COUNT(*)                       AS orders_paid
                  FROM base
                 WHERE status = 'PAID'
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """
                        .formatted(LOCAL);
        return jdbc.query(
                sql,
                params(filter),
                (rs, i) -> new SalesStats.HeatCell(
                        rs.getInt("day_of_week"),
                        rs.getInt("hour"),
                        rs.getLong("gross_vnd"),
                        rs.getLong("orders_paid")));
    }

    @Override
    public List<SalesStats.SizeBucket> ticketsPerOrder(SalesStats.Filter filter) {
        String sql = base(filter)
                + """
                SELECT tickets, COUNT(*) AS orders
                  FROM base
                 WHERE status = 'PAID'
                 GROUP BY tickets
                 ORDER BY tickets
                """;
        return jdbc.query(
                sql, params(filter), (rs, i) -> new SalesStats.SizeBucket(rs.getInt("tickets"), rs.getLong("orders")));
    }

    @Override
    public List<SalesStats.OrderRow> orders(
            SalesStats.Filter filter, List<String> statuses, String orderNumberPrefix, int limit, int offset) {
        // Database gom mọi đơn của kỳ rồi mới cắt trang: chi phí tỉ lệ với số đơn trong KỲ, không
        // phải với cả bảng — kỳ đã bị chặn trần ở SalesStatsQuery và lọc bằng idx_order_organization.
        String sql = base(filter, "o.order_number, o.total_vnd", orderFilter(statuses, orderNumberPrefix))
                + """
                SELECT id, order_number, event_id, event_session_id, status, tickets, total_vnd, created_at, paid_at
                  FROM base
                 ORDER BY created_at DESC, id
                 LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(
                sql,
                orderParams(filter, statuses, orderNumberPrefix)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, i) -> {
                    Timestamp paidAt = rs.getTimestamp("paid_at");
                    return new SalesStats.OrderRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("order_number"),
                            rs.getObject("event_id", UUID.class),
                            rs.getObject("event_session_id", UUID.class),
                            rs.getString("status"),
                            rs.getLong("tickets"),
                            rs.getLong("total_vnd"),
                            rs.getTimestamp("created_at").toInstant(),
                            paidAt == null ? null : paidAt.toInstant());
                });
    }

    @Override
    public long countOrders(SalesStats.Filter filter, List<String> statuses, String orderNumberPrefix) {
        String sql = base(filter, "o.order_number, o.total_vnd", orderFilter(statuses, orderNumberPrefix))
                + "SELECT COUNT(*) FROM base";
        Long total = jdbc.queryForObject(sql, orderParams(filter, statuses, orderNumberPrefix), Long.class);
        return total == null ? 0 : total;
    }

    /**
     * "Quay lại" hỏi lịch sử TRƯỚC kỳ của từng người mua: mỗi lượt tra đi bằng {@code idx_order_user}
     * ({@code user_id, created_at}), nên chi phí tỉ lệ với số người mua trong kỳ, không phải cả bảng.
     */
    @Override
    public long[] buyerCounts(SalesStats.Filter filter) {
        String sql = base(filter)
                + """
                , buyers AS (
                    SELECT user_id, COUNT(*) AS orders FROM base WHERE status = 'PAID' GROUP BY user_id
                )
                SELECT COUNT(*) AS buyers,
                       COUNT(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM orders p
                            WHERE p.user_id = b.user_id
                              AND p.organization_id = :organizationId
                              AND p.status = 'PAID'
                              AND p.created_at < :from)) AS returning,
                       COUNT(*) FILTER (WHERE b.orders > 1) AS repeat_in_period
                  FROM buyers b
                """;
        return jdbc.queryForObject(sql, params(filter), (rs, i) ->
                new long[] {rs.getLong("buyers"), rs.getLong("returning"), rs.getLong("repeat_in_period")});
    }

    @Override
    public List<SalesStats.SizeBucket> ordersPerBuyer(SalesStats.Filter filter) {
        String sql = base(filter)
                + """
                , buyers AS (
                    SELECT user_id, COUNT(*) AS orders FROM base WHERE status = 'PAID' GROUP BY user_id
                )
                SELECT LEAST(orders, 5) AS orders, COUNT(*) AS buyers
                  FROM buyers
                 GROUP BY 1
                 ORDER BY 1
                """;
        return jdbc.query(
                sql, params(filter), (rs, i) -> new SalesStats.SizeBucket(rs.getInt("orders"), rs.getLong("buyers")));
    }

    private static String orderFilter(List<String> statuses, String orderNumberPrefix) {
        StringBuilder sql = new StringBuilder();
        if (!statuses.isEmpty()) {
            sql.append(" AND o.status IN (:statuses)");
        }
        if (orderNumberPrefix != null) {
            sql.append(" AND o.order_number ILIKE :orderNumber ESCAPE '\\'");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource orderParams(
            SalesStats.Filter filter, List<String> statuses, String orderNumberPrefix) {
        MapSqlParameterSource params = params(filter).addValue("statuses", statuses);
        if (orderNumberPrefix != null) {
            // Ký tự đại diện trong từ khoá là chữ thường, không phải mẫu: gõ "%" không được trả về
            // mọi đơn.
            String literal =
                    orderNumberPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.addValue("orderNumber", literal + "%");
        }
        return params;
    }

    private static String base(SalesStats.Filter filter) {
        return base(filter, "", "");
    }

    private static String base(SalesStats.Filter filter, String extraColumns, String extraWhere) {
        return """
                WITH base AS (
                    SELECT o.id, o.status, o.created_at, o.paid_at, o.user_id, o.event_id, o.event_session_id,
                           %s
                           COUNT(*)                               AS tickets,
                           SUM(i.unit_price_vnd - i.discount_vnd) AS amount
                      FROM orders o
                      JOIN order_items i ON i.order_id = o.id
                     WHERE %s%s
                     GROUP BY o.id
                )
                """
                .formatted(extraColumns.isEmpty() ? "" : extraColumns + ",", where(filter), extraWhere);
    }

    /** Chỉ ghép những điều kiện có giá trị: {@code IN ()} rỗng là lỗi cú pháp, không phải "không lọc". */
    private static String where(SalesStats.Filter filter) {
        StringBuilder sql = new StringBuilder(
                "o.organization_id = :organizationId AND o.created_at >= :from AND o.created_at < :to");
        if (!filter.eventIds().isEmpty()) {
            sql.append(" AND o.event_id IN (:eventIds)");
        }
        if (!filter.sessionIds().isEmpty()) {
            sql.append(" AND o.event_session_id IN (:sessionIds)");
        }
        if (!filter.ticketTypeIds().isEmpty()) {
            sql.append(" AND i.ticket_type_id IN (:ticketTypeIds)");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource params(SalesStats.Filter filter) {
        return new MapSqlParameterSource()
                .addValue("organizationId", filter.organizationId())
                .addValue("from", Timestamp.from(filter.from()))
                .addValue("to", Timestamp.from(filter.to()))
                .addValue("eventIds", filter.eventIds())
                .addValue("sessionIds", filter.sessionIds())
                .addValue("ticketTypeIds", filter.ticketTypeIds());
    }
}
