// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.persistence;

import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.model.WebhookOutcome;
import com.nexaticket.payment.domain.port.PaymentAttemptRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentAttemptRepository implements PaymentAttemptRepository {

    private final JdbcTemplate jdbc;

    public JdbcPaymentAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID webhookEventId, UUID intentId, BankTransfer transfer, WebhookOutcome outcome, String note) {
        jdbc.update(
                """
                INSERT INTO payment_attempts (id, intent_id, webhook_event_id, amount_received_vnd,
                                              received_bank_bin, received_account, raw_reference, outcome, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                intentId,
                webhookEventId,
                transfer.amountVnd(),
                transfer.receivingBankBin(),
                transfer.receivingAccountNumber(),
                transfer.rawContent(),
                outcome.name(),
                note);
    }
}
