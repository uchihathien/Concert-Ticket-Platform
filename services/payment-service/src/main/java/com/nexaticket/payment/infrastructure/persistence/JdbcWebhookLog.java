// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.persistence;

import com.nexaticket.payment.domain.port.WebhookLog;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Nhật ký webhook, giữ payload nguyên văn dưới dạng {@code jsonb}. */
@Repository
public class JdbcWebhookLog implements WebhookLog {

    private final JdbcTemplate jdbc;

    public JdbcWebhookLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Entry entry) {
        jdbc.update(
                """
                INSERT INTO bank_webhook_log (
                    id, provider, provider_txn_id, payos_order_code, reference, amount_vnd,
                    raw_payload, outcome, note)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                UUID.randomUUID(),
                entry.provider(),
                entry.providerTxnId(),
                entry.payosOrderCode(),
                entry.reference(),
                entry.amountVnd(),
                // Cột là jsonb: một chuỗi không phải JSON sẽ làm cả câu INSERT vỡ, và vỡ ở đây nghĩa là
                // MẤT dòng nhật ký của một khoản tiền thật. Người gọi đã đảm bảo, đây là lưới cuối.
                jsonOrEmpty(entry.rawJson()),
                entry.outcome(),
                entry.note());
    }

    private static String jsonOrEmpty(String rawJson) {
        return rawJson == null || rawJson.isBlank() ? "{}" : rawJson;
    }
}
