// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.persistence;

import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.SagaStatus;
import com.nexaticket.ordering.domain.port.CheckoutSagaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCheckoutSagaRepository implements CheckoutSagaRepository {

    private final JdbcTemplate jdbc;

    public JdbcCheckoutSagaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(CheckoutSaga saga) {
        jdbc.update(
                """
                INSERT INTO checkout_sagas (order_id, hold_id, user_id, status,
                                            seats_reserved, payment_intent_open, attempts, last_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                saga.orderId(),
                saga.holdId(),
                saga.userId(),
                saga.status().name(),
                saga.isSeatsReserved(),
                saga.isPaymentIntentOpen(),
                saga.attempts(),
                saga.lastError());
    }

    @Override
    public void update(CheckoutSaga saga) {
        jdbc.update(
                """
                UPDATE checkout_sagas
                   SET status = ?, seats_reserved = ?, payment_intent_open = ?,
                       attempts = ?, last_error = ?, updated_at = now()
                 WHERE order_id = ?
                """,
                saga.status().name(),
                saga.isSeatsReserved(),
                saga.isPaymentIntentOpen(),
                saga.attempts(),
                saga.lastError(),
                saga.orderId());
    }

    @Override
    public Optional<CheckoutSaga> findByOrderId(UUID orderId) {
        return jdbc
                .query(
                        """
                        SELECT order_id, hold_id, user_id, status, seats_reserved,
                               payment_intent_open, attempts
                          FROM checkout_sagas WHERE order_id = ?
                        """,
                        JdbcCheckoutSagaRepository::map,
                        orderId)
                .stream()
                .findFirst();
    }

    @Override
    public List<CheckoutSaga> claimCompensationPending(Instant notUpdatedSince, int batchSize) {
        return jdbc.query(
                """
                SELECT order_id, hold_id, user_id, status, seats_reserved,
                       payment_intent_open, attempts
                  FROM checkout_sagas
                 WHERE status = 'COMPENSATION_PENDING' AND updated_at <= ?
                 ORDER BY updated_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                JdbcCheckoutSagaRepository::map,
                Timestamp.from(notUpdatedSince),
                batchSize);
    }

    private static CheckoutSaga map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return CheckoutSaga.rehydrate(
                rs.getObject("order_id", UUID.class),
                rs.getObject("hold_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                SagaStatus.valueOf(rs.getString("status")),
                rs.getBoolean("seats_reserved"),
                rs.getBoolean("payment_intent_open"),
                rs.getInt("attempts"));
    }
}
