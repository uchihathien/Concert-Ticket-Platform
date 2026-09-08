// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.persistence;

import com.nexaticket.payment.domain.model.WebhookOutcome;
import com.nexaticket.payment.domain.port.WebhookEventRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWebhookEventRepository implements WebhookEventRepository {

    private final JdbcTemplate jdbc;

    public JdbcWebhookEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> recordIfNew(String provider, String providerEventId, String payloadHash) {
        // ON CONFLICT DO NOTHING + RETURNING: chèn được thì có dòng trả về, đụng độ thì
        // không có dòng nào. Một câu lệnh, nguyên tử — kiểm bằng SELECT rồi mới INSERT sẽ
        // để hai webhook song song cùng thấy "chưa có" và cùng đi tiếp.
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO webhook_events (id, provider, provider_event_id, payload_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                RETURNING id
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                UUID.randomUUID(),
                provider,
                providerEventId,
                payloadHash);
        return inserted.stream().findFirst();
    }

    @Override
    public void markProcessed(UUID webhookEventId, WebhookOutcome outcome, String note) {
        jdbc.update(
                "UPDATE webhook_events SET processing_status = 'PROCESSED', outcome = ?, note = ? WHERE id = ?",
                outcome.name(),
                note,
                webhookEventId);
    }
}
