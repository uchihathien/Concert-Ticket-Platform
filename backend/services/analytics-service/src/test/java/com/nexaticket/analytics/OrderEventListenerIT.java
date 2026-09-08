// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.analytics.domain.port.SalesReadModelRepository;
import com.nexaticket.analytics.infrastructure.amqp.OrderEventListener;
import com.nexaticket.platform.test.PostgresSingleton;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Consumer nhận đúng payload mà ordering-service thật sự phát ra.
 *
 * <p>Bộ test kia gọi thẳng handler với dữ liệu tự dựng, nên nó không bao giờ chạm tới câu hỏi
 * <b>"payload thật có đúng những trường ta đọc không"</b>. Đó là chỗ hai service lệch nhau mà cả
 * hai bên đều xanh — và lệch đúng kiểu đó đã xảy ra ở đây: {@code order.paid} không mang
 * {@code eventId}, trong khi read model từng khai cột đó là NOT NULL.
 *
 * <p>Message được dựng tay thay vì qua broker: thứ cần kiểm là hợp đồng payload, không phải khả
 * năng nói chuyện AMQP.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderEventListenerIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("analytics_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @Autowired
    OrderEventListener listener;

    @Autowired
    SalesReadModelRepository readModel;

    @Autowired
    JdbcTemplate jdbc;

    private UUID sessionId;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE session_sales, processed_events");
        sessionId = UUID.randomUUID();
        organizationId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Payload order.paid ĐÚNG NHƯ ordering phát: cộng vào read model được")
    void payload_that_cua_ordering() {
        listener.onOrderEvent(orderPaid(UUID.randomUUID(), 2, 3_000_000L));

        var sales = readModel.bySession(sessionId).orElseThrow();
        assertThat(sales.ticketsSold()).isEqualTo(2);
        assertThat(sales.grossVnd()).isEqualTo(3_000_000L);
        assertThat(sales.ordersPaid()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cùng một message giao hai lần: chỉ cộng một lần")
    void message_trung_chi_cong_mot_lan() {
        UUID messageId = UUID.randomUUID();

        listener.onOrderEvent(orderPaid(messageId, 2, 3_000_000L));
        listener.onOrderEvent(orderPaid(messageId, 2, 3_000_000L));

        assertThat(readModel.bySession(sessionId).orElseThrow().grossVnd()).isEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("Loại sự kiện chưa biết: bỏ qua, KHÔNG nổ")
    void su_kien_la_thi_bo_qua() {
        // Ordering có thể thêm loại sự kiện mới bất cứ lúc nào. Nếu consumer nack mọi thứ nó
        // chưa hiểu thì một lần thêm sự kiện ở service khác sẽ làm nghẽn queue của service này.
        listener.onOrderEvent(message(UUID.randomUUID(), "order.something.new", basePayload(1, 1_000L)));

        assertThat(readModel.bySession(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Message không có messageId: bỏ qua và không nổ")
    void thieu_message_id() {
        Message broken = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setHeader("eventType", "order.paid")
                .build();

        listener.onOrderEvent(broken);

        assertThat(readModel.bySession(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Thân message không phải JSON hợp lệ: bỏ qua và không nổ")
    void payload_rac() {
        Message broken = MessageBuilder.withBody("khong-phai-json".getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .setHeader("eventType", "order.paid")
                .build();

        listener.onOrderEvent(broken);

        assertThat(readModel.bySession(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Đơn hết hạn và đơn huỷ được đếm riêng, không đụng doanh thu")
    void don_het_han_va_huy() {
        listener.onOrderEvent(message(UUID.randomUUID(), "order.expired", basePayload(2, 3_000_000L)));
        listener.onOrderEvent(message(UUID.randomUUID(), "order.cancelled", basePayload(1, 1_500_000L)));

        var sales = readModel.bySession(sessionId).orElseThrow();
        assertThat(sales.ordersExpired()).isEqualTo(1);
        assertThat(sales.ordersCancelled()).isEqualTo(1);
        assertThat(sales.grossVnd()).isZero();
        assertThat(sales.ticketsSold()).isZero();
    }

    // --- dựng message đúng hình dạng OutboxAdapter của ordering-service ---

    private Message orderPaid(UUID messageId, int ticketCount, long totalVnd) {
        Map<String, Object> payload = basePayload(ticketCount, totalVnd);
        payload.put("commissionBps", 500);
        payload.put("commissionVnd", totalVnd / 20);
        payload.put("paidAt", java.time.Instant.now().toString());
        return message(messageId, "order.paid", payload);
    }

    private Map<String, Object> basePayload(int ticketCount, long totalVnd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", UUID.randomUUID().toString());
        payload.put("orderNumber", "NT-260908-K7M2QP");
        payload.put("organizationId", organizationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("totalVnd", totalVnd);
        payload.put("ticketCount", ticketCount);
        return payload;
    }

    private static Message message(UUID messageId, String eventType, Map<String, Object> payload) {
        try {
            String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                    .setMessageId(messageId.toString())
                    .setHeader("eventType", eventType)
                    .setHeader("aggregateId", UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
