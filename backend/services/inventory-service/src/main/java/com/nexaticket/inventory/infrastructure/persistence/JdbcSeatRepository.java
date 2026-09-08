// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.persistence;

import com.nexaticket.inventory.domain.port.SeatRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter persistence của tồn kho.
 *
 * <p>Mọi lệnh đổi trạng thái đều là UPDATE có điều kiện trên {@code status}. Không có chỗ nào
 * đọc-rồi-ghi, vì khoảng trống giữa hai lệnh đó chính là chỗ oversell chui qua.
 */
@Repository
public class JdbcSeatRepository implements SeatRepository {

    private static final List<String> OCCUPIED = List.of("HELD", "RESERVED", "SOLD");

    private final JdbcTemplate jdbc;

    public JdbcSeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockCustomerSession(UUID userId, UUID eventSessionId) {
        // hashtextextended cho 64 bit, đủ thưa để hai cặp khác nhau gần như không đụng khoá.
        // xact: khoá tự nhả khi transaction kết thúc, không cần unlock tay — quan trọng vì
        // đường giữ chỗ có nhiều nhánh ném ngoại lệ.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))",
                Object.class,
                userId.toString(),
                eventSessionId.toString());
    }

    @Override
    public int countUnitsHeldBy(UUID eventSessionId, UUID userId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM session_seats
                 WHERE event_session_id = ? AND holder_user_id = ?
                   AND status IN ('HELD', 'RESERVED', 'SOLD')
                """,
                Integer.class,
                eventSessionId,
                userId);
        return count == null ? 0 : count;
    }

    @Override
    public int holdSeated(UUID eventSessionId, List<UUID> seatIds, UUID userId) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        // Khoá theo THỨ TỰ id trước khi ghi. Hai khách chọn giao nhau {A,B} và {B,A} mà khoá
        // theo thứ tự khách gửi thì deadlock; ORDER BY id ép mọi transaction khoá cùng một chiều.
        jdbc.query(
                """
                SELECT id FROM session_seats
                 WHERE event_session_id = ? AND id = ANY(?) AND status = 'AVAILABLE'
                 ORDER BY id
                   FOR UPDATE
                """,
                ps -> {
                    ps.setObject(1, eventSessionId);
                    ps.setArray(2, UuidArrays.of(ps, seatIds));
                },
                (rs, i) -> rs.getObject("id", UUID.class));

        return jdbc.update(
                """
                UPDATE session_seats
                   SET status = 'HELD', holder_user_id = ?
                 WHERE event_session_id = ? AND id = ANY(?) AND status = 'AVAILABLE'
                """,
                ps -> {
                    ps.setObject(1, userId);
                    ps.setObject(2, eventSessionId);
                    ps.setArray(3, UuidArrays.of(ps, seatIds));
                });
    }

    @Override
    public List<UUID> allocateStanding(UUID eventSessionId, String zoneCode, int quantity, UUID userId) {
        // SKIP LOCKED: bỏ qua hàng đang bị transaction khác giữ thay vì xếp hàng chờ.
        // Với vé đứng khách không quan tâm cấp đơn vị nào, nên bỏ qua là đúng nghiệp vụ —
        // và nhờ vậy 500 luồng cùng mua không biến thành 500 lượt chờ nối đuôi (ADR-1012).
        List<UUID> picked = jdbc.query(
                """
                SELECT id FROM session_seats
                 WHERE event_session_id = ? AND zone_code = ?
                   AND admission_type = 'STANDING' AND status = 'AVAILABLE'
                 ORDER BY id
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                eventSessionId,
                zoneCode,
                quantity);

        if (picked.isEmpty()) {
            return List.of();
        }
        jdbc.update("UPDATE session_seats SET status = 'HELD', holder_user_id = ? WHERE id = ANY(?)", ps -> {
            ps.setObject(1, userId);
            ps.setArray(2, UuidArrays.of(ps, picked));
        });
        return picked;
    }

    @Override
    public int reserve(List<UUID> seatIds) {
        return transition(seatIds, "RESERVED", List.of("HELD"), true);
    }

    @Override
    public int markSold(List<UUID> seatIds) {
        return transition(seatIds, "SOLD", List.of("RESERVED"), true);
    }

    @Override
    public int release(List<UUID> seatIds) {
        // holder_user_id = NULL là phần quan trọng: quên nó thì khách bị khoá oan hạn mức
        // cho suất đó (ADR-1014). CHECK ck_holder_matches_status trong database chặn việc quên.
        return transition(seatIds, "AVAILABLE", OCCUPIED, false);
    }

    private int transition(List<UUID> seatIds, String target, List<String> from, boolean keepHolder) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        String holderClause = keepHolder ? "" : ", holder_user_id = NULL";
        String sql = "UPDATE session_seats SET status = ?" + holderClause + " WHERE id = ANY(?) AND status = ANY(?)";
        return jdbc.update(sql, ps -> {
            ps.setString(1, target);
            ps.setArray(2, UuidArrays.of(ps, seatIds));
            ps.setArray(3, ps.getConnection().createArrayOf("text", from.toArray()));
        });
    }

    @Override
    public List<SeatRow> seatedRows(UUID eventSessionId) {
        return jdbc.query(
                """
                SELECT id, seat_code, zone_code, section_label, row_label, seat_label,
                       pos_x, pos_y, ticket_type_id, ticket_type_name, price_vnd, status
                  FROM session_seats
                 WHERE event_session_id = ? AND admission_type = 'SEATED'
                 ORDER BY zone_code, row_label, seat_label
                """,
                (rs, i) -> new SeatRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("seat_code"),
                        rs.getString("zone_code"),
                        rs.getString("section_label"),
                        rs.getString("row_label"),
                        rs.getString("seat_label"),
                        rs.getBigDecimal("pos_x"),
                        rs.getBigDecimal("pos_y"),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        rs.getLong("price_vnd"),
                        rs.getString("status")),
                eventSessionId);
    }

    @Override
    public List<StandingZoneRow> standingZones(UUID eventSessionId) {
        // Gộp thành một hàng mỗi zone. Trả từng đơn vị ảo xuống client là 3.000 phần tử
        // vô nghĩa với khách và payload phình vô ích (ADR-1012).
        return jdbc.query(
                """
                SELECT zone_code, ticket_type_id, ticket_type_name, price_vnd,
                       COUNT(*) FILTER (WHERE status = 'AVAILABLE') AS available,
                       COUNT(*) FILTER (WHERE status <> 'BLOCKED')  AS capacity
                  FROM session_seats
                 WHERE event_session_id = ? AND admission_type = 'STANDING'
                 GROUP BY zone_code, ticket_type_id, ticket_type_name, price_vnd
                 ORDER BY zone_code
                """,
                (rs, i) -> new StandingZoneRow(
                        rs.getString("zone_code"),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        rs.getLong("price_vnd"),
                        rs.getInt("available"),
                        rs.getInt("capacity")),
                eventSessionId);
    }
}
