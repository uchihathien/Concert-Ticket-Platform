// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.application.query.GateStats;
import com.nexaticket.ticketing.application.query.GateStatsQuery;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class GateStatsIT extends TicketingTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Autowired
    private GateStatsQuery stats;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID org = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Test
    @DisplayName("Vào, ra, vào lại và bị từ chối được đếm đúng nhánh; mốc đến tính theo lần vào đầu")
    void hoat_dong_soat_ve() {
        UUID first = ticket("Nguyễn Văn An");
        UUID second = ticket("Trần Thị Bình");
        log(first, staff, "ACCEPTED", "IN", "QR", "Cổng A", at(19, 2));
        log(first, staff, "EXITED", "OUT", "QR", "Cổng A", at(19, 40));
        log(first, staff, "ACCEPTED", "IN", "QR", "Cổng A", at(19, 55));
        log(second, owner, "ACCEPTED", "IN", "OVERRIDE", "Cổng B", at(19, 20));
        log(second, staff, "ALREADY_CHECKED_IN", "IN", "QR", "Cổng A", at(19, 21));
        log(null, staff, "INVALID_TOKEN", "IN", "QR", "Cổng A", at(19, 22));

        GateStats.Activity activity = stats.activity(org, DAY, DAY, null);

        assertThat(activity.totals()).isEqualTo(new GateStats.LogTotals(6, 3, 2, 1, 2));
        assertThat(activity.rejectedByReason())
                .containsExactlyInAnyOrder(
                        new GateStats.Count("ALREADY_CHECKED_IN", 1), new GateStats.Count("INVALID_TOKEN", 1));
        assertThat(activity.entriesByMethod())
                .containsExactlyInAnyOrder(new GateStats.Count("QR", 2), new GateStats.Count("OVERRIDE", 1));

        GateStats.SessionFlow flow = activity.sessions().get(0);
        assertThat(flow.firstEntryAt()).isEqualTo(at(19, 2).toInstant());
        // Lần vào lại lúc 19:55 không phải là "người đến muộn nhất": mốc tính theo lần vào ĐẦU.
        assertThat(flow.lastEntryAt()).isEqualTo(at(19, 20).toInstant());
        assertThat(flow.averageEntryAt()).isEqualTo(at(19, 11).toInstant());

        GateStats.ScannerRow byStaff = activity.scanners().get(0);
        assertThat(byStaff.userId()).isEqualTo(staff);
        assertThat(byStaff.accepted()).isEqualTo(2);
        assertThat(byStaff.rejected()).isEqualTo(2);
        assertThat(byStaff.activeMinutes()).isEqualTo(5);
        assertThat(activity.scanners().get(1).overrides()).isEqualTo(1);

        assertThat(activity.overrides()).hasSize(1);
        assertThat(activity.gates()).extracting(GateStats.GateRow::gate).containsExactly("Cổng A", "Cổng B");
        // 19:02 → ô 19:00; 19:20–19:22 → ô 19:15; 19:40 → ô 19:30; 19:55 → ô 19:45.
        assertThat(activity.timeline())
                .extracting(GateStats.FlowPoint::bucket)
                .containsExactly(
                        LocalDateTime.of(2026, 3, 10, 19, 0),
                        LocalDateTime.of(2026, 3, 10, 19, 15),
                        LocalDateTime.of(2026, 3, 10, 19, 30),
                        LocalDateTime.of(2026, 3, 10, 19, 45));
    }

    @Test
    @DisplayName("Tổ chức khác không thấy nhật ký, kể cả khi truyền đúng mã suất; tên người mua bị che")
    void khong_ro_sang_to_chuc_khac() {
        UUID ticket = ticket("Nguyễn Văn An");
        log(ticket, staff, "ACCEPTED", "IN", "QR", null, at(19, 2));
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM tickets WHERE id = ?", UUID.class, ticket);
        UUID stranger = UUID.randomUUID();

        assertThat(stats.activity(stranger, DAY, DAY, List.of(session)).totals().scans())
                .isZero();
        assertThat(stats.buyers(stranger, List.of(orderId)).buyers()).isEmpty();
        assertThat(stats.buyers(org, List.of(orderId)).buyers())
                .containsExactly(new GateStats.Buyer(orderId, "Nguyễn V. A."));
        // Ngoài kỳ thì không có gì.
        assertThat(stats.activity(org, DAY.plusDays(1), DAY.plusDays(1), null)
                        .totals()
                        .scans())
                .isZero();
    }

    private static Timestamp at(int hour, int minute) {
        return Timestamp.from(LocalDateTime.of(2026, 3, 10, hour, minute)
                .atZone(GateStatsQuery.ZONE)
                .toInstant());
    }

    private UUID ticket(String holder) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO tickets (id, order_id, order_item_id, event_session_id, organization_id, user_id,
                                     session_seat_id, seat_code, zone_code, admission_type, ticket_type_name,
                                     holder_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'A-1', 'A', 'STANDING', 'Thường', ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                session,
                org,
                UUID.randomUUID(),
                UUID.randomUUID(),
                holder);
        return id;
    }

    private void log(UUID ticket, UUID by, String result, String direction, String method, String gate, Timestamp at) {
        jdbc.update(
                """
                INSERT INTO checkin_log (id, ticket_id, event_session_id, scanned_by, result, direction,
                                         entry_method, gate, scanned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ticket,
                session,
                by,
                result,
                direction,
                method,
                gate,
                at);
    }
}
