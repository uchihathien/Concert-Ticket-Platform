// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.analytics.application.command.ApplySalesDeltaHandler;
import com.nexaticket.analytics.application.query.SalesQueries;
import com.nexaticket.analytics.domain.model.SalesDelta;
import com.nexaticket.analytics.domain.port.SalesReadModelRepository;
import com.nexaticket.platform.test.PostgresSingleton;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Read model bán hàng.
 *
 * <p>Hai yêu cầu bắt buộc với mọi consumer (ADR-1009) đều được kiểm ở đây: <b>idempotent</b> và
 * <b>không phụ thuộc thứ tự</b>. Read model xây bằng phép cộng dồn nên không cái nào tự có.
 */
@SpringBootTest
@ActiveProfiles("test")
class SalesReadModelIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("analytics_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @Autowired
    ApplySalesDeltaHandler apply;

    @Autowired
    SalesReadModelRepository readModel;

    @Autowired
    SalesQueries queries;

    @Autowired
    JdbcTemplate jdbc;

    private UUID sessionId;
    private UUID eventId;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE session_sales, processed_events");
        sessionId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        organizationId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Cộng dồn nhiều đơn cho ra tổng đúng")
    void cong_don_nhieu_don() {
        apply.handle(UUID.randomUUID(), "order.paid", paid(2, 3_000_000L));
        apply.handle(UUID.randomUUID(), "order.paid", paid(3, 4_500_000L));

        var sales = readModel.bySession(sessionId).orElseThrow();
        assertThat(sales.ticketsSold()).isEqualTo(5);
        assertThat(sales.grossVnd()).isEqualTo(7_500_000L);
        assertThat(sales.ordersPaid()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cùng một sự kiện xử lý lại: KHÔNG cộng lần hai")
    void xu_ly_lai_khong_cong_lan_hai() {
        // Cộng dồn không tự idempotent, và RabbitMQ giao ít nhất một lần nên message trùng
        // chắc chắn xảy ra. Không có processed_events thì doanh thu tự nhân đôi.
        UUID eventMessageId = UUID.randomUUID();

        assertThat(apply.handle(eventMessageId, "order.paid", paid(2, 3_000_000L)))
                .isTrue();
        assertThat(apply.handle(eventMessageId, "order.paid", paid(2, 3_000_000L)))
                .isFalse();

        assertThat(readModel.bySession(sessionId).orElseThrow().grossVnd()).isEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("Sự kiện đến ĐẢO THỨ TỰ vẫn cho tổng đúng")
    void dao_thu_tu_van_dung() {
        // RabbitMQ không bảo đảm thứ tự. Nếu message mang "tổng số vé tính đến lúc này" thay
        // vì delta, thì message đến trễ sẽ ghi đè một con số mới bằng một con số cũ.
        apply.handle(UUID.randomUUID(), "order.refunded", refunded(1, 1_500_000L));
        apply.handle(UUID.randomUUID(), "order.paid", paid(2, 3_000_000L));
        apply.handle(UUID.randomUUID(), "order.expired", expired());

        var sales = readModel.bySession(sessionId).orElseThrow();
        assertThat(sales.ticketsSold()).isEqualTo(1);
        assertThat(sales.grossVnd()).isEqualTo(1_500_000L);
        assertThat(sales.ordersExpired()).isEqualTo(1);
    }

    @Test
    @DisplayName("Hoàn tiền TRỪ ngược, không xoá dòng khỏi báo cáo")
    void hoan_tien_tru_nguoc() {
        // Xoá dòng sẽ làm suất diễn biến mất khỏi dashboard của tổ chức.
        apply.handle(UUID.randomUUID(), "order.paid", paid(3, 4_500_000L));
        apply.handle(UUID.randomUUID(), "order.refunded", refunded(1, 1_500_000L));

        var sales = readModel.bySession(sessionId).orElseThrow();
        assertThat(sales.ticketsSold()).isEqualTo(2);
        assertThat(sales.grossVnd()).isEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("Dashboard tổ chức cộng đúng nhiều suất diễn")
    void dashboard_cong_nhieu_suat() {
        apply.handle(UUID.randomUUID(), "order.paid", paid(2, 3_000_000L));
        sessionId = UUID.randomUUID();
        apply.handle(UUID.randomUUID(), "order.paid", paid(5, 8_000_000L));

        var summary = queries.forOrganization(organizationId);
        assertThat(summary.sessions()).hasSize(2);
        assertThat(summary.totalTicketsSold()).isEqualTo(7);
        assertThat(summary.totalGrossVnd()).isEqualTo(11_000_000L);
    }

    @Test
    @DisplayName("Read model KHÔNG có cột hoa hồng — ranh giới cứng của ADR-1010")
    void khong_co_cot_hoa_hong() {
        // Một cột tồn tại là một cột sẽ lọt ra API sau vài lần sửa vội. Test này khoá lại
        // ranh giới giữa miền của tổ chức và miền tài chính của superadmin.
        Integer commissionColumns = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_name = 'session_sales'
                   AND (column_name LIKE '%commission%' OR column_name LIKE '%payout%'
                        OR column_name LIKE '%balance%')
                """,
                Integer.class);

        assertThat(commissionColumns).isZero();
    }

    @Test
    @DisplayName("Đọc theo sự kiện: gom mọi suất của nó, và chỉ của nó")
    void doc_theo_su_kien() {
        // Đường đọc của bảng điều khiển tổ chức bên catalog. Lọc theo event_id chứ không lọc trong
        // bộ nhớ từ danh sách của cả tổ chức: một tổ chức lớn có hàng trăm suất, còn màn hình
        // master data chỉ cần vài suất của một sự kiện.
        apply.handle(UUID.randomUUID(), "order.paid", paid(2, 3_000_000L));

        UUID suatKhac = UUID.randomUUID();
        apply.handle(
                UUID.randomUUID(),
                "order.paid",
                SalesDelta.orderPaid(suatKhac, eventId, organizationId, 1, 1_000_000L));

        // Suất của một sự kiện KHÁC, cùng tổ chức — không được lọt vào.
        apply.handle(
                UUID.randomUUID(),
                "order.paid",
                SalesDelta.orderPaid(UUID.randomUUID(), UUID.randomUUID(), organizationId, 9, 9_000_000L));

        var rows = queries.forEvent(eventId);

        assertThat(rows).hasSize(2);
        assertThat(rows.stream()
                        .mapToInt(SalesQueries.SessionSalesView::ticketsSold)
                        .sum())
                .isEqualTo(3);
        assertThat(rows.stream()
                        .mapToLong(SalesQueries.SessionSalesView::grossVnd)
                        .sum())
                .isEqualTo(4_000_000L);
        assertThat(rows).allMatch(row -> row.eventId().equals(eventId));
    }

    private SalesDelta paid(int tickets, long grossVnd) {
        return SalesDelta.orderPaid(sessionId, eventId, organizationId, tickets, grossVnd);
    }

    private SalesDelta refunded(int tickets, long grossVnd) {
        return SalesDelta.refunded(sessionId, eventId, organizationId, tickets, grossVnd);
    }

    private SalesDelta expired() {
        return SalesDelta.orderExpired(sessionId, eventId, organizationId);
    }
}
