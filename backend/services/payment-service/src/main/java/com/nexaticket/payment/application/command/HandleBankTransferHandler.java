// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentReference;
import com.nexaticket.payment.domain.model.TransferClassification;
import com.nexaticket.payment.domain.model.WebhookOutcome;
import com.nexaticket.payment.domain.port.OrderingPort;
import com.nexaticket.payment.domain.port.PaymentAttemptRepository;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.WebhookEventRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xử lý một giao dịch chuyển khoản đến — spike webhook (plan/backend.md §5).
 *
 * <p>Thứ tự các bước là phần quan trọng nhất, và không được đổi:
 *
 * <ol>
 *   <li><b>Dedupe trước tiên</b> bằng {@code INSERT ... ON CONFLICT DO NOTHING}. Đây là thứ duy
 *       nhất đóng được race giữa hai webhook trùng đến <i>song song</i>.
 *   <li><b>Khoá dòng intent</b> bằng {@code SELECT ... FOR UPDATE}, chống race với worker hết hạn
 *       đang chạy trên đúng đơn đó (BRAINSTORM §4.4).
 *   <li><b>Phân loại</b> bằng hàm thuần {@link TransferClassification}.
 *   <li><b>Ghi attempt</b> — kể cả khi không khớp, vì đó là danh sách việc của người đối soát.
 *   <li>Chỉ khi {@code CONFIRMED} mới báo Ordering.
 * </ol>
 */
@Service
public class HandleBankTransferHandler {

    private static final Logger log = LoggerFactory.getLogger(HandleBankTransferHandler.class);
    private static final String PROVIDER = "SEPAY";

    private final WebhookEventRepository events;
    private final PaymentIntentRepository intents;
    private final PaymentAttemptRepository attempts;
    private final OrderingPort ordering;
    private final Clock clock;

    public HandleBankTransferHandler(
            WebhookEventRepository events,
            PaymentIntentRepository intents,
            PaymentAttemptRepository attempts,
            OrderingPort ordering,
            Clock clock) {
        this.events = events;
        this.intents = intents;
        this.attempts = attempts;
        this.ordering = ordering;
        this.clock = clock;
    }

    /**
     * @return kết luận, để controller quyết mã HTTP trả về cho nhà cung cấp
     */
    @Transactional
    public WebhookOutcome handle(BankTransfer transfer, String payloadHash) {
        Optional<UUID> eventId = events.recordIfNew(PROVIDER, transfer.providerEventId(), payloadHash);
        if (eventId.isEmpty()) {
            // Đã xử lý transaction này. Trả 2xx để nhà cung cấp ngừng retry — trả lỗi ở đây sẽ
            // khiến họ retry mãi một việc đã xong.
            log.info("Webhook {} đã xử lý trước đó, bỏ qua", transfer.providerEventId());
            return WebhookOutcome.DUPLICATE;
        }

        Optional<PaymentIntent> intent = PaymentReference.findIn(transfer.rawContent())
                .flatMap(reference -> intents.lockByReference(reference.value()));

        var verdict = TransferClassification.classify(transfer, intent, clock.instant());

        attempts.record(
                eventId.get(), intent.map(PaymentIntent::id).orElse(null), transfer, verdict.outcome(), verdict.note());

        if (verdict.outcome() == WebhookOutcome.CONFIRMED) {
            PaymentIntent confirmed = intent.orElseThrow();
            if (confirmed.confirm(clock.instant())) {
                intents.updateStatus(confirmed);
            }
            // Gọi đồng bộ và trong transaction: nếu Ordering hỏng, ngoại lệ làm rollback cả
            // dòng dedupe, nên nhà cung cấp retry và ta xử lý lại từ đầu. Ghi 2xx trước rồi
            // mới báo Ordering sẽ để lại đơn đã trả tiền mà mãi không PAID.
            ordering.confirmPayment(confirmed.orderId());
        }

        if (verdict.needsHumanReview()) {
            log.warn(
                    "Giao dịch {} cần đối soát tay: {} — {} đồng",
                    transfer.providerEventId(),
                    verdict.note(),
                    transfer.amountVnd());
        }
        events.markProcessed(eventId.get(), verdict.outcome(), verdict.note());
        return verdict.outcome();
    }
}
