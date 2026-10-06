// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.id.CorrelationContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ghi integration event vào outbox <b>trong cùng transaction</b> với thay đổi nghiệp vụ (ADR-0005).
 *
 * <p>Không bao giờ publish lên broker trước khi database commit.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @param exchange ví dụ {@code nexaticket.identity}
     * @param eventType routing key, ví dụ {@code organization.created}
     */
    public void append(String exchange, String aggregateType, UUID aggregateId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxWriter phải được gọi bên trong transaction — nếu không, event có thể "
                            + "được publish cho một thay đổi đã rollback");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không serialize được payload cho " + eventType, e);
        }
        jdbc.update(
                """
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, exchange,
                                    payload, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                exchange,
                json,
                CorrelationContext.current());
    }
}
