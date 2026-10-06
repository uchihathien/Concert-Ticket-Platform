// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.infrastructure.persistence;

import com.nexaticket.analytics.domain.model.SalesDelta;
import com.nexaticket.analytics.domain.port.SalesReadModelRepository;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSalesReadModelRepository implements SalesReadModelRepository {

    /** Tên hàng đợi của consumer này; khoá dedupe gồm cả tên này (starter-idempotency). */
    private static final String CONSUMER_QUEUE = "analytics.sales";

    private final JdbcTemplate jdbc;
    private final ProcessedEvents processedEvents;

    public JdbcSalesReadModelRepository(JdbcTemplate jdbc, ProcessedEvents processedEvents) {
        this.jdbc = jdbc;
        this.processedEvents = processedEvents;
    }

    @Override
    public boolean applyIfNew(UUID eventId, String eventType, SalesDelta delta) {
        // Ghi dấu TRƯỚC, cộng SAU, trong cùng một transaction. Trả false nghĩa là sự kiện đã
        // xử lý — dừng ngay, không cộng lần hai.
        if (!processedEvents.markIfNew(CONSUMER_QUEUE, eventId.toString())) {
            return false;
        }

        jdbc.update(
                """
                INSERT INTO session_sales (event_session_id, event_id, organization_id, tickets_sold,
                                           gross_vnd, orders_paid, orders_expired, orders_cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_session_id) DO UPDATE
                   SET tickets_sold     = session_sales.tickets_sold + EXCLUDED.tickets_sold,
                       gross_vnd        = session_sales.gross_vnd + EXCLUDED.gross_vnd,
                       orders_paid      = session_sales.orders_paid + EXCLUDED.orders_paid,
                       orders_expired   = session_sales.orders_expired + EXCLUDED.orders_expired,
                       orders_cancelled = session_sales.orders_cancelled + EXCLUDED.orders_cancelled,
                       updated_at       = now()
                """,
                delta.eventSessionId(),
                delta.eventId(),
                delta.organizationId(),
                delta.ticketsDelta(),
                delta.grossDeltaVnd(),
                delta.ordersPaidDelta(),
                delta.ordersExpiredDelta(),
                delta.ordersCancelledDelta());
        return true;
    }

    @Override
    public Optional<SessionSales> bySession(UUID eventSessionId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM session_sales WHERE event_session_id = ?",
                        JdbcSalesReadModelRepository::map,
                        eventSessionId)
                .stream()
                .findFirst();
    }

    @Override
    public List<SessionSales> byOrganization(UUID organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM session_sales WHERE organization_id = ? ORDER BY updated_at DESC",
                JdbcSalesReadModelRepository::map,
                organizationId);
    }

    @Override
    public List<SessionSales> byEvent(UUID eventId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM session_sales WHERE event_id = ? ORDER BY updated_at DESC",
                JdbcSalesReadModelRepository::map,
                eventId);
    }

    private static final String COLUMNS =
            """
            event_session_id, event_id, organization_id, tickets_sold, gross_vnd,
            orders_paid, orders_expired, orders_cancelled
            """;

    private static SessionSales map(ResultSet rs, int rowNum) throws SQLException {
        return new SessionSales(
                rs.getObject("event_session_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getInt("tickets_sold"),
                rs.getLong("gross_vnd"),
                rs.getInt("orders_paid"),
                rs.getInt("orders_expired"),
                rs.getInt("orders_cancelled"));
    }

    /**
     * Xếp hạng bằng một câu GROUP BY, không kéo cả bảng về rồi cộng trong Java.
     *
     * <p>`idx_sales_event` phục vụ phép gộp này. Bảng chỉ có một dòng cho mỗi SUẤT diễn — không
     * phải mỗi vé — nên nó nhỏ hơn số vé bán ra vài bậc và phép gộp rẻ kể cả khi quét toàn bảng.
     *
     * <p>Loại sự kiện chưa bán vé nào ra khỏi kết quả: một hàng "0 vé" không nói lên điều gì về độ
     * hot, và để nó lọt vào thì danh sách "đang hot" sẽ đầy những sự kiện chưa ai mua khi hệ thống
     * mới chạy.
     */
    @Override
    public List<EventPopularity> trendingEvents(int limit) {
        return jdbc.query(
                """
                SELECT event_id, SUM(tickets_sold) AS sold
                  FROM session_sales
                 GROUP BY event_id
                HAVING SUM(tickets_sold) > 0
                 ORDER BY sold DESC, event_id
                 LIMIT ?
                """,
                (rs, i) -> new EventPopularity(rs.getObject("event_id", UUID.class), rs.getInt("sold")),
                limit);
    }
}
