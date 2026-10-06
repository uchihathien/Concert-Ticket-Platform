// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đẩy các dòng outbox chưa gửi lên RabbitMQ.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} cho phép chạy nhiều instance mà không giẫm chân nhau.
 *
 * <h3>Chỉ đánh dấu đã gửi sau khi broker xác nhận</h3>
 *
 * <p>{@code RabbitTemplate.send} là <b>bất đồng bộ</b> và không ném lỗi khi broker từ chối. Gửi rồi
 * cập nhật {@code published_at} ngay là mất message âm thầm — và mất đúng vào lúc tệ nhất: khi
 * broker chưa khai exchange, tức là lần đầu dựng môi trường hoặc ngay sau một lần khôi phục. Cả
 * database lẫn log đều nói "đã gửi", còn consumer thì không bao giờ nhận được gì.
 *
 * <p>Đã xảy ra thật: sáu sự kiện {@code session.published} biến mất vì exchange
 * {@code nexaticket.catalog} chưa tồn tại. Broker trả lỗi kênh 404, {@code send} không ném, và cả
 * sáu dòng outbox được đánh dấu là đã gửi.
 *
 * <p>Nên ở đây mỗi message mang một {@link CorrelationData} và ta <b>chờ xác nhận</b> trước khi
 * đánh dấu. Chưa xác nhận thì để nguyên dòng đó: vòng sau gửi lại. Hệ quả là có thể gửi trùng —
 * đúng như hợp đồng at-least-once đã chọn (ADR-1009), và consumer vốn đã phải idempotent.
 *
 * <p>Gửi hết cả lô rồi mới chờ, không gửi-chờ từng cái: chờ từng cái biến một lô 200 message thành
 * 200 vòng khứ hồi nối tiếp.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 200;

    /**
     * Chờ xác nhận tối đa bao lâu.
     *
     * <p>Đủ dài để không báo động vì một lần mạng chậm, đủ ngắn để không giữ transaction — và cả
     * khoá {@code FOR UPDATE} — quá lâu khi broker chết hẳn.
     */
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${nexaticket.outbox.publish-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<Row> rows = jdbc.query(
                """
                SELECT id, seq, aggregate_type, aggregate_id, event_type, event_version,
                       exchange, payload::text, correlation_id
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY seq
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getLong("seq"),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("exchange"),
                        rs.getString("payload"),
                        rs.getString("correlation_id")),
                BATCH_SIZE);

        if (rows.isEmpty()) {
            return;
        }

        List<Pending> pending = new ArrayList<>(rows.size());
        for (Row row : rows) {
            CorrelationData correlation = new CorrelationData(row.id().toString());
            rabbit.send(row.exchange(), row.eventType(), messageOf(row), correlation);
            pending.add(new Pending(row, correlation));
        }

        int confirmed = 0;
        for (Pending entry : pending) {
            if (isAcked(entry)) {
                jdbc.update(
                        "UPDATE outbox SET published_at = now() WHERE id = ?",
                        entry.row().id());
                confirmed++;
            }
        }

        if (confirmed < rows.size()) {
            // Không ném lỗi: những dòng đã xác nhận cần được commit, và những dòng còn lại sẽ được
            // gửi lại ở vòng sau. Ném lỗi ở đây sẽ rollback cả lô và gửi lại cả những message
            // broker đã nhận.
            log.warn("Outbox: {}/{} message được broker xác nhận, phần còn lại sẽ gửi lại", confirmed, rows.size());
        } else {
            log.debug("Đã publish {} event từ outbox", confirmed);
        }
    }

    /**
     * Broker đã nhận message này chưa.
     *
     * <p>Hết giờ chờ hoặc bị ngắt đều tính là chưa — dòng outbox giữ nguyên và vòng sau gửi lại.
     * Đoán rằng "chắc là tới rồi" chính là cách message biến mất.
     */
    private static boolean isAcked(Pending entry) {
        try {
            CorrelationData.Confirm confirm =
                    entry.correlation().getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.warn(
                        "Broker từ chối event {} ({}): {}",
                        entry.row().id(),
                        entry.row().eventType(),
                        confirm == null ? "không có phản hồi" : confirm.getReason());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn(
                    "Chưa nhận được xác nhận cho event {} ({}), sẽ gửi lại: {}",
                    entry.row().id(),
                    entry.row().eventType(),
                    e.toString());
            return false;
        }
    }

    private static Message messageOf(Row row) {
        return MessageBuilder.withBody(row.payload().getBytes())
                .setContentType("application/json")
                .setMessageId(row.id().toString())
                .setHeader("eventType", row.eventType())
                .setHeader("eventVersion", row.eventVersion())
                .setHeader("aggregateType", row.aggregateType())
                .setHeader("aggregateId", row.aggregateId().toString())
                .setHeader("correlationId", row.correlationId())
                .setHeader("occurredAt", Instant.now().toString())
                .build();
    }

    private record Pending(Row row, CorrelationData correlation) {}

    private record Row(
            UUID id,
            long seq,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int eventVersion,
            String exchange,
            String payload,
            String correlationId) {}
}
