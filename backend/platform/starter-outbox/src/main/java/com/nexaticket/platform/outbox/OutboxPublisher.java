// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đẩy các dòng outbox chưa gửi lên RabbitMQ.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} cho phép chạy nhiều instance mà không giẫm chân nhau. Chỉ đánh
 * dấu {@code published_at} sau khi broker xác nhận (publisher confirms).
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 200;

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

        for (Row row : rows) {
            Message message = MessageBuilder.withBody(row.payload().getBytes())
                    .setContentType("application/json")
                    .setMessageId(row.id().toString())
                    .setHeader("eventType", row.eventType())
                    .setHeader("eventVersion", row.eventVersion())
                    .setHeader("aggregateType", row.aggregateType())
                    .setHeader("aggregateId", row.aggregateId().toString())
                    .setHeader("correlationId", row.correlationId())
                    .setHeader("occurredAt", Instant.now().toString())
                    .build();
            rabbit.send(row.exchange(), row.eventType(), message);
            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.id());
        }

        if (!rows.isEmpty()) {
            log.debug("Đã publish {} event từ outbox", rows.size());
        }
    }

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
