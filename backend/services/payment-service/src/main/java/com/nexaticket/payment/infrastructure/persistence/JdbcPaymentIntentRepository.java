// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.persistence;

import com.nexaticket.payment.domain.model.IntentStatus;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentIntentRepository implements PaymentIntentRepository {

    private static final String COLUMNS =
            """
            id, order_id, organization_id, amount_vnd, payment_reference, bank_account_id,
            bank_bin, bank_account_number, vietqr_payload, expires_at, status, confirmed_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcPaymentIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PaymentIntent intent) {
        jdbc.update(
                """
                INSERT INTO payment_intents (id, order_id, organization_id, amount_vnd, payment_reference,
                                             bank_account_id, bank_bin, bank_account_number, vietqr_payload,
                                             status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                intent.id(),
                intent.orderId(),
                intent.organizationId(),
                intent.amountVnd(),
                intent.reference().value(),
                intent.bankAccountId(),
                intent.bankBin(),
                intent.bankAccountNumber(),
                intent.vietQrPayload(),
                intent.status().name(),
                Timestamp.from(intent.expiresAt()));
    }

    @Override
    public Optional<PaymentIntent> findByOrderId(UUID orderId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM payment_intents WHERE order_id = ?",
                        JdbcPaymentIntentRepository::map,
                        orderId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<PaymentIntent> lockByReference(String paymentReference) {
        // FOR UPDATE: webhook và worker hết hạn có thể chạm cùng một dòng cùng lúc
        // (BRAINSTORM §4.4). Không khoá thì cả hai đọc PENDING và kết quả tuỳ ai commit sau.
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM payment_intents WHERE payment_reference = ? FOR UPDATE",
                        JdbcPaymentIntentRepository::map,
                        paymentReference)
                .stream()
                .findFirst();
    }

    @Override
    public void updateStatus(PaymentIntent intent) {
        jdbc.update(
                """
                UPDATE payment_intents
                   SET status = ?, confirmed_at = ?, closed_at = CASE WHEN ? IN ('CANCELLED', 'EXPIRED')
                                                                      THEN now() ELSE closed_at END
                 WHERE id = ?
                """,
                intent.status().name(),
                intent.confirmedAt() == null ? null : Timestamp.from(intent.confirmedAt()),
                intent.status().name(),
                intent.id());
    }

    @Override
    public List<PaymentIntent> claimExpired(Instant now, int batchSize) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + """
                         FROM payment_intents
                         WHERE status = 'PENDING' AND expires_at <= ?
                         ORDER BY expires_at
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """,
                JdbcPaymentIntentRepository::map,
                Timestamp.from(now),
                batchSize);
    }

    private static PaymentIntent map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        return PaymentIntent.rehydrate(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getLong("amount_vnd"),
                rs.getString("payment_reference"),
                rs.getObject("bank_account_id", UUID.class),
                rs.getString("bank_bin"),
                rs.getString("bank_account_number"),
                rs.getString("vietqr_payload"),
                rs.getTimestamp("expires_at").toInstant(),
                IntentStatus.valueOf(rs.getString("status")),
                confirmedAt == null ? null : confirmedAt.toInstant());
    }
}
