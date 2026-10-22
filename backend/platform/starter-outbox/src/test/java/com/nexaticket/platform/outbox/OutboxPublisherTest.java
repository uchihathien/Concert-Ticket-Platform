// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link OutboxPublisher}: chỉ đánh dấu {@code published_at} sau khi broker <b>xác nhận</b>.
 *
 * <p>JdbcTemplate và RabbitTemplate đều là mock. Mỗi dòng outbox giả được đưa qua đúng
 * {@link RowMapper} của publisher bằng một {@link ResultSet} giả, nên phần bóc cột cũng được kiểm.
 * Xác nhận của broker được giả bằng cách hoàn tất {@code CorrelationData.getFuture()} ngay lúc
 * {@code send} — không có chờ thật, không có hết giờ thật.
 */
class OutboxPublisherTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(jdbc, rabbit);

    private final List<Message> sent = new ArrayList<>();

    record Row(UUID id, long seq, String eventType, String exchange, String payload, String correlationId) {}

    private static final UUID AGG = UUID.fromString("eeeeeeee-0000-4000-a000-000000000005");

    private static Row row(long seq, String eventType) {
        return new Row(UUID.randomUUID(), seq, eventType, "nexaticket.ordering", "{\"n\":" + seq + "}", "corr-" + seq);
    }

    @BeforeEach
    void chua_co_dong_nao() {
        stubRows(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubRows(List<Row> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(200))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                mapped.add(mapper.mapRow(resultSet(rows.get(i)), i));
            }
            return mapped;
        });
    }

    private static ResultSet resultSet(Row row) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(row.id());
        when(rs.getLong("seq")).thenReturn(row.seq());
        when(rs.getString("aggregate_type")).thenReturn("Order");
        when(rs.getObject("aggregate_id", UUID.class)).thenReturn(AGG);
        when(rs.getString("event_type")).thenReturn(row.eventType());
        when(rs.getInt("event_version")).thenReturn(2);
        when(rs.getString("exchange")).thenReturn(row.exchange());
        when(rs.getString("payload")).thenReturn(row.payload());
        when(rs.getString("correlation_id")).thenReturn(row.correlationId());
        return rs;
    }

    /** Broker trả lời mỗi message theo hàm cho trước: null = để future treo (không dùng ở đây). */
    private void brokerAnswers(Function<Message, Object> answer) {
        doAnswer(invocation -> {
                    Message message = invocation.getArgument(2);
                    CorrelationData correlation = invocation.getArgument(3);
                    sent.add(message);
                    Object outcome = answer.apply(message);
                    if (outcome instanceof CorrelationData.Confirm confirm) {
                        correlation.getFuture().complete(confirm);
                    } else if (outcome instanceof Throwable failure) {
                        correlation.getFuture().completeExceptionally(failure);
                    }
                    return null;
                })
                .when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private List<Object> markedPublished() {
        ArgumentCaptor<Object> ids = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, org.mockito.Mockito.atLeast(0))
                .update(eq("UPDATE outbox SET published_at = now() WHERE id = ?"), ids.capture());
        return ids.getAllValues();
    }

    @Test
    @DisplayName("không có dòng nào chờ gửi: không đụng tới broker")
    void khong_co_dong_nao() {
        publisher.publishPending();

        verifyNoInteractions(rabbit);
    }

    @Test
    @DisplayName("broker xác nhận cả lô: mọi dòng được đánh dấu đã gửi, theo đúng thứ tự seq")
    void xac_nhan_ca_lo() {
        Row first = row(1, "order.created");
        Row second = row(2, "order.paid");
        stubRows(List.of(first, second));
        brokerAnswers(m -> new CorrelationData.Confirm(true, null));

        publisher.publishPending();

        assertThat(markedPublished()).containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("message mang đủ header: messageId = id dòng outbox, eventType, version, aggregate, correlationId")
    void message_mang_du_header() {
        Row only = row(7, "order.paid");
        stubRows(List.of(only));
        brokerAnswers(m -> new CorrelationData.Confirm(true, null));

        publisher.publishPending();

        verify(rabbit)
                .send(eq("nexaticket.ordering"), eq("order.paid"), any(Message.class), any(CorrelationData.class));
        Message message = sent.get(0);
        var props = message.getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(only.id().toString());
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat((Object) props.getHeader("eventType")).isEqualTo("order.paid");
        assertThat((Object) props.getHeader("eventVersion")).isEqualTo(2);
        assertThat((Object) props.getHeader("aggregateType")).isEqualTo("Order");
        assertThat((Object) props.getHeader("aggregateId")).isEqualTo(AGG.toString());
        assertThat((Object) props.getHeader("correlationId")).isEqualTo("corr-7");
        assertThat((Object) props.getHeader("occurredAt")).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"n\":7}");
    }

    @Test
    @DisplayName("broker từ chối (nack) một message: dòng đó KHÔNG được đánh dấu, các dòng khác vẫn được")
    void nack_khong_danh_dau() {
        Row ok = row(1, "order.created");
        Row rejected = row(2, "order.paid");
        Row ok2 = row(3, "order.cancelled");
        stubRows(List.of(ok, rejected, ok2));
        brokerAnswers(m ->
                m.getMessageProperties().getMessageId().equals(rejected.id().toString())
                        ? new CorrelationData.Confirm(false, "NOT_FOUND - no exchange")
                        : new CorrelationData.Confirm(true, null));

        publisher.publishPending();

        assertThat(markedPublished()).containsExactly(ok.id(), ok2.id());
    }

    @Test
    @DisplayName("kênh lỗi trước khi xác nhận: không đánh dấu — vòng sau gửi lại, không đoán 'chắc tới rồi'")
    void loi_kenh_khong_danh_dau() {
        stubRows(List.of(row(1, "order.paid")));
        brokerAnswers(m -> new IllegalStateException("channel closed"));

        publisher.publishPending();

        verify(jdbc, never()).update(eq("UPDATE outbox SET published_at = now() WHERE id = ?"), any(Object.class));
    }

    @Test
    @DisplayName("payload tiếng Việt đi lên dây dưới dạng UTF-8")
    void payload_tieng_viet_la_utf8() {
        Row vietnamese =
                new Row(UUID.randomUUID(), 1, "event.published", "nexaticket.catalog", "{\"ten\":\"Đêm nhạc\"}", null);
        stubRows(List.of(vietnamese));
        brokerAnswers(m -> new CorrelationData.Confirm(true, null));

        publisher.publishPending();

        assertThat(sent.get(0).getBody()).isEqualTo("{\"ten\":\"Đêm nhạc\"}".getBytes(StandardCharsets.UTF_8));
    }
}
