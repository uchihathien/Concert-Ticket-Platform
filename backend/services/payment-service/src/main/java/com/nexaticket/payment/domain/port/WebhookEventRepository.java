// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.WebhookOutcome;
import java.util.Optional;
import java.util.UUID;

/** Chống xử lý trùng webhook. */
public interface WebhookEventRepository {

    /**
     * Ghi nhận một webhook nếu chưa từng thấy.
     *
     * <p>Cài bằng {@code INSERT ... ON CONFLICT DO NOTHING}. Đây là bước ĐẦU TIÊN của handler và
     * là thứ duy nhất đóng được race giữa hai webhook trùng đến <b>song song</b> — kiểm bằng
     * {@code SELECT} rồi mới {@code INSERT} thì cả hai đều thấy "chưa có" và cả hai đều đi tiếp.
     *
     * @return id của bản ghi nếu đây là lần đầu; rỗng nếu đã xử lý rồi
     */
    Optional<UUID> recordIfNew(String provider, String providerEventId, String payloadHash);

    void markProcessed(UUID webhookEventId, WebhookOutcome outcome, String note);
}
