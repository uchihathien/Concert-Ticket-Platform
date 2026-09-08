// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.query.PaymentIntentView;
import com.nexaticket.payment.domain.model.EscrowBankAccount;
import com.nexaticket.payment.domain.model.IntentStatus;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.EscrowAccountRepository;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mở yêu cầu thanh toán và sinh mã VietQR.
 *
 * <p>Idempotent theo {@code orderId}: saga checkout gọi lại sau timeout mạng phải nhận đúng mã QR
 * cũ. Sinh mã mới sẽ để lại hai mã tham chiếu cùng trỏ một đơn, và nếu khách đã quét mã đầu thì
 * tiền về với mã mà hệ thống vừa quên mất.
 */
@Service
public class OpenPaymentIntentHandler {

    private final PaymentIntentRepository intents;
    private final EscrowAccountRepository accounts;

    public OpenPaymentIntentHandler(PaymentIntentRepository intents, EscrowAccountRepository accounts) {
        this.intents = intents;
        this.accounts = accounts;
    }

    public record Command(UUID orderId, UUID organizationId, long amountVnd, Instant expiresAt) {}

    /** Trả view của tầng application, không trả aggregate ra ngoài (tactical-ddd.md §7). */
    @Transactional
    public PaymentIntentView open(Command cmd) {
        return PaymentIntentView.from(handle(cmd));
    }

    @Transactional
    public PaymentIntent handle(Command cmd) {
        var existing = intents.findByOrderId(cmd.orderId());
        if (existing.isPresent()) {
            return existing.get();
        }

        EscrowBankAccount account = accounts.preferred()
                .orElseThrow(() -> new ApiException(
                        PaymentErrorCode.NO_ESCROW_ACCOUNT, "No preferred escrow bank account is configured"));

        PaymentIntent intent =
                PaymentIntent.open(cmd.orderId(), cmd.organizationId(), cmd.amountVnd(), account, cmd.expiresAt());
        intents.save(intent);
        return intent;
    }

    /** Bù trừ của saga checkout. Idempotent — job quét có thể gọi lại. */
    @Transactional
    public void cancel(UUID orderId) {
        intents.findByOrderId(orderId).ifPresent(intent -> {
            if (intent.close(IntentStatus.CANCELLED)) {
                intents.updateStatus(intent);
            }
        });
    }
}
