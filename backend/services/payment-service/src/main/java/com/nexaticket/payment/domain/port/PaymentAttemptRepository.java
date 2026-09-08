// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.model.WebhookOutcome;
import java.util.UUID;

/**
 * Nhật ký mọi giao dịch ngân hàng đã chạm vào hệ thống.
 *
 * <p>Ghi cả những lần KHÔNG khớp: đó chính là danh sách việc của người đối soát. Một giao dịch có
 * tiền mà không có dòng nào ở đây là một giao dịch vô chủ.
 */
public interface PaymentAttemptRepository {

    void record(UUID webhookEventId, UUID intentId, BankTransfer transfer, WebhookOutcome outcome, String note);
}
