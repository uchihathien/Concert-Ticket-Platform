// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dựng một suất diễn đã materialize, đúng như catalog-service sẽ làm khi publish.
 *
 * <p>Chèn thẳng SQL chứ không gọi API: mục đích của bộ test là kiểm tra đường giữ chỗ, nên phần
 * dựng dữ liệu phải đơn giản và không phụ thuộc service chưa tồn tại.
 */
@Component
public class InventoryFixture {

    private final JdbcTemplate jdbc;

    public InventoryFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param seatedCount số ghế ngồi, đặt tên A-1 … A-n
     * @param standingCapacity số đơn vị ảo vé đứng ở zone {@code GA}
     */
    public Session materialize(int seatedCount, int standingCapacity) {
        return materialize(seatedCount, standingCapacity, 8, 10, 10, 10);
    }

    public Session materialize(
            int seatedCount,
            int standingCapacity,
            int maxSeatedPerHold,
            int maxStandingPerHold,
            int maxUnitsPerHold,
            int maxTicketsPerCustomer) {

        UUID sessionId = UUID.randomUUID();
        UUID seatedType = UUID.randomUUID();
        UUID standingType = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                """
                INSERT INTO session_inventory (
                    id, event_session_id, event_id, organization_id,
                    sales_open_at, sales_close_at,
                    max_seated_per_hold, max_standing_per_hold,
                    max_units_per_hold, max_tickets_per_customer,
                    hold_ttl_seconds, materialized_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                sessionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
                maxSeatedPerHold,
                maxStandingPerHold,
                maxUnitsPerHold,
                maxTicketsPerCustomer,
                600,
                Timestamp.from(now));

        List<UUID> seatedIds = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= seatedCount; i++) {
            UUID id = UUID.randomUUID();
            seatedIds.add(id);
            rows.add(new Object[] {
                id,
                sessionId,
                "A-" + i,
                "A",
                "SEATED",
                "Khan dai A",
                "1",
                String.valueOf(i),
                seatedType,
                "Ve ngoi",
                1_500_000L
            });
        }
        // Đơn vị ảo của vé đứng: cùng bảng, cùng chốt chặn, chỉ khác cách chọn (ADR-1012).
        for (int i = 1; i <= standingCapacity; i++) {
            rows.add(new Object[] {
                UUID.randomUUID(),
                sessionId,
                "GA-GA-%06d".formatted(i),
                "GA",
                "STANDING",
                null,
                null,
                null,
                standingType,
                "Ve dung",
                800_000L
            });
        }
        jdbc.batchUpdate(
                """
                INSERT INTO session_seats (
                    id, event_session_id, seat_code, zone_code, admission_type,
                    section_label, row_label, seat_label,
                    ticket_type_id, ticket_type_name, price_vnd)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows);

        return new Session(sessionId, List.copyOf(seatedIds));
    }

    public record Session(UUID id, List<UUID> seatIds) {}

    public int countByStatus(UUID sessionId, String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_seats WHERE event_session_id = ? AND status = ?",
                Integer.class,
                sessionId,
                status);
        return n == null ? 0 : n;
    }

    public int countHolders(UUID sessionId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_seats WHERE event_session_id = ? AND holder_user_id IS NOT NULL",
                Integer.class,
                sessionId);
        return n == null ? 0 : n;
    }

    /** Số dòng đang chiếm chốt chặn oversell. Phải luôn bằng số chỗ đang HELD. */
    public int countActiveHoldItems(UUID sessionId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM seat_hold_items i
                  JOIN session_seats s ON s.id = i.session_seat_id
                 WHERE s.event_session_id = ? AND i.status = 'ACTIVE'
                """,
                Integer.class,
                sessionId);
        return n == null ? 0 : n;
    }

    public void expireHold(UUID holdId) {
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 minute' WHERE id = ?", holdId);
    }
}
