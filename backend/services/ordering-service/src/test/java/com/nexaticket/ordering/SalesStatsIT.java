// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ordering.application.query.SalesStats;
import com.nexaticket.ordering.application.query.SalesStatsQuery;
import com.nexaticket.ordering.support.OrderingTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SalesStatsIT extends OrderingTestBase {

    @Autowired
    private SalesStatsQuery stats;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID org = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();
    private final UUID standard = UUID.randomUUID();

    @Test
    @DisplayName("Doanh thu chỉ tính đơn đã trả; phễu đếm mỗi đơn một lần dù có nhiều vé")
    void tong_hop_theo_ky() {
        order("PAID", local(2026, 3, 10, 20, 0), vip, 500_000, vip, 500_000, standard, 200_000);
        order("PAID", local(2026, 3, 11, 9, 30), standard, 200_000);
        order("EXPIRED", local(2026, 3, 11, 10, 0), vip, 500_000);
        order("CANCELLED", local(2026, 3, 12, 10, 0), standard, 200_000);
        // Ngoài kỳ: không được lọt vào kỳ đang xem, nhưng là "kỳ trước" khi so sánh.
        order("PAID", local(2026, 3, 5, 12, 0), standard, 200_000);

        SalesStats.Summary summary = stats.summary(request(null), true);

        SalesStats.Totals now = summary.current();
        assertThat(now.ordersTotal()).isEqualTo(4);
        assertThat(now.ordersPaid()).isEqualTo(2);
        assertThat(now.ordersExpired()).isEqualTo(1);
        assertThat(now.ordersCancelled()).isEqualTo(1);
        assertThat(now.grossVnd()).isEqualTo(1_400_000);
        assertThat(now.ticketsSold()).isEqualTo(4);
        assertThat(summary.previousPeriod())
                .isEqualTo(new SalesStats.Period(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 9)));
        assertThat(summary.previous().grossVnd()).isEqualTo(200_000);
    }

    @Test
    @DisplayName("Gom theo ngày Việt Nam và lấp ngày trống bằng 0")
    void chuoi_thoi_gian() {
        // 23:30 ngày 10 giờ Việt Nam là 16:30 UTC cùng ngày; 00:30 ngày 11 là 17:30 UTC ngày 10.
        // Gom theo UTC sẽ dồn cả hai vào ngày 10.
        order("PAID", local(2026, 3, 10, 23, 30), standard, 200_000);
        order("PAID", local(2026, 3, 11, 0, 30), vip, 500_000);

        SalesStats.Series series = stats.series(request(null), "day", false);

        assertThat(series.current()).hasSize(7);
        assertThat(series.current().get(0).bucket()).isEqualTo(LocalDateTime.of(2026, 3, 10, 0, 0));
        assertThat(series.current().get(0).grossVnd()).isEqualTo(200_000);
        assertThat(series.current().get(1).grossVnd()).isEqualTo(500_000);
        assertThat(series.current().get(2).grossVnd()).isZero();
        assertThat(series.current().stream()
                        .mapToLong(SalesStats.Point::grossVnd)
                        .sum())
                .isEqualTo(stats.summary(request(null), false).current().grossVnd());
    }

    @Test
    @DisplayName("Lọc theo loại vé: doanh thu chỉ còn phần của loại ấy, và khớp với bảng phân rã")
    void loc_theo_loai_ve() {
        order("PAID", local(2026, 3, 10, 20, 0), vip, 500_000, standard, 200_000);
        order("PAID", local(2026, 3, 11, 20, 0), standard, 200_000);

        SalesStats.Totals onlyVip = stats.summary(request(List.of(vip)), false).current();
        assertThat(onlyVip.grossVnd()).isEqualTo(500_000);
        assertThat(onlyVip.ticketsSold()).isEqualTo(1);
        assertThat(onlyVip.ordersPaid()).isEqualTo(1);

        SalesStats.Breakdown breakdown = stats.breakdown(request(null));
        assertThat(breakdown.sessions()).hasSize(1);
        assertThat(breakdown.sessions().get(0).grossVnd()).isEqualTo(900_000);
        assertThat(breakdown.ticketTypes().stream()
                        .mapToLong(SalesStats.TicketTypeRow::grossVnd)
                        .sum())
                .isEqualTo(900_000);
    }

    @Test
    @DisplayName("Tổ chức khác không thấy gì, kể cả khi truyền đúng mã sự kiện")
    void khong_ro_sang_to_chuc_khac() {
        order("PAID", local(2026, 3, 10, 20, 0), vip, 500_000);

        var other = new SalesStatsQuery.Request(
                UUID.randomUUID(), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 16), List.of(event), null, null);

        assertThat(stats.summary(other, false).current().ordersTotal()).isZero();
        assertThat(stats.breakdown(other).sessions()).isEmpty();
        assertThat(stats.heatmap(other).cells()).isEmpty();
    }

    @Test
    @DisplayName("Kỳ không có đơn nào: mọi tổng bằng 0 và thời gian trả là null, không phải 0 giây")
    void ky_rong() {
        SalesStats.Totals totals = stats.summary(request(null), false).current();

        assertThat(totals.ordersTotal()).isZero();
        assertThat(totals.grossVnd()).isZero();
        assertThat(totals.avgSecondsToPay()).isNull();
    }

    @Test
    @DisplayName("Khách quay lại là người đã có đơn đã trả TRƯỚC kỳ; đơn quá hạn không tính")
    void khach_moi_va_quay_lai() {
        UUID loyal = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID browser = UUID.randomUUID();
        orderBy(loyal, "PAID", local(2026, 2, 1, 10, 0));
        orderBy(loyal, "PAID", local(2026, 3, 10, 10, 0));
        orderBy(loyal, "PAID", local(2026, 3, 12, 10, 0));
        orderBy(fresh, "PAID", local(2026, 3, 11, 10, 0));
        // Chỉ có đơn quá hạn trước kỳ: vẫn là khách MỚI khi mua thật lần đầu.
        orderBy(browser, "EXPIRED", local(2026, 2, 1, 10, 0));
        orderBy(browser, "PAID", local(2026, 3, 13, 10, 0));

        SalesStats.Customers customers = stats.customers(request(null));

        assertThat(customers.buyers()).isEqualTo(3);
        assertThat(customers.returning()).isEqualTo(1);
        assertThat(customers.newBuyers()).isEqualTo(2);
        assertThat(customers.repeatInPeriod()).isEqualTo(1);
        assertThat(customers.ordersPerBuyer())
                .containsExactly(new SalesStats.SizeBucket(1, 2), new SalesStats.SizeBucket(2, 1));
    }

    @Test
    @DisplayName("Khoảng thời gian bị chặn trần; gom theo giờ có trần chặt hơn")
    void chan_khoang_thoi_gian() {
        var tooLong =
                new SalesStatsQuery.Request(org, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1), null, null, null);
        assertThatThrownBy(() -> stats.summary(tooLong, false)).isInstanceOf(ApiException.class);

        var month =
                new SalesStatsQuery.Request(org, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, null);
        assertThatThrownBy(() -> stats.series(month, "hour", false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> stats.series(month, "minute", false)).isInstanceOf(ApiException.class);
        assertThat(stats.series(month, "week", false).current()).isNotEmpty();
    }

    private SalesStatsQuery.Request request(List<UUID> ticketTypes) {
        return new SalesStatsQuery.Request(
                org, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 16), null, null, ticketTypes);
    }

    private static Timestamp local(int year, int month, int day, int hour, int minute) {
        return Timestamp.from(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(SalesStatsQuery.ZONE)
                .toInstant());
    }

    private void orderBy(UUID userId, String status, Timestamp createdAt) {
        order(userId, status, createdAt, standard, 200_000);
    }

    /** @param items các cặp (mã loại vé, giá) nối tiếp nhau */
    private void order(String status, Timestamp createdAt, Object... items) {
        order(UUID.randomUUID(), status, createdAt, items);
    }

    private void order(UUID userId, String status, Timestamp createdAt, Object... items) {
        UUID orderId = UUID.randomUUID();
        long total = 0;
        for (int i = 1; i < items.length; i += 2) {
            total += ((Number) items[i]).longValue();
        }
        boolean paid = "PAID".equals(status);
        jdbc.update(
                """
                INSERT INTO orders (id, order_number, event_session_id, event_id, organization_id, user_id, hold_id,
                                    status, subtotal_vnd, total_vnd, commission_bps, commission_vnd,
                                    payment_expires_at, created_at, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)
                """,
                orderId,
                "T-" + orderId,
                session,
                event,
                org,
                userId,
                UUID.randomUUID(),
                status,
                total,
                total,
                createdAt,
                createdAt,
                paid ? createdAt : null);
        for (int i = 0; i < items.length; i += 2) {
            UUID type = (UUID) items[i];
            jdbc.update(
                    """
                    INSERT INTO order_items (id, order_id, session_seat_id, seat_code, zone_code, admission_type,
                                             ticket_type_id, ticket_type_name, unit_price_vnd, commission_bps,
                                             commission_vnd)
                    VALUES (?, ?, ?, ?, 'A', 'STANDING', ?, ?, ?, 0, 0)
                    """,
                    UUID.randomUUID(),
                    orderId,
                    UUID.randomUUID(),
                    "S" + i,
                    type,
                    type.equals(vip) ? "VIP" : "Thường",
                    ((Number) items[i + 1]).longValue());
        }
    }
}
