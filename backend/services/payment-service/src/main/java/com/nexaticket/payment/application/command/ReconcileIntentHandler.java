// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Kéo trạng thái một link về từ payOS, thay vì chờ webhook đẩy sang.
 *
 * <p><b>Vì sao cần.</b> Webhook payOS đòi một URL HTTPS gọi được từ internet. Ở máy phát triển điều đó
 * cần một tunnel và thường không có, nên webhook <b>không bao giờ tới</b> — mà payOS cũng
 * <b>không có môi trường sandbox</b>: mọi lần thử là tiền thật trên production. Không có đường kéo
 * trạng thái về thì mỗi lần thử luồng thanh toán kết thúc bằng một đơn đã trả tiền mà không có vé.
 *
 * <p>Ở production nó vẫn cần, vì lý do khác: một lần deploy đúng lúc tiền vào, hoặc payOS hết lượt
 * retry, đều để lại một đơn đã trả tiền mà hệ thống không biết. Trước đây việc đó phải sửa bằng SQL tay.
 *
 * <p><b>Không tự tin gì vào payOS hơn webhook.</b> Nó đi qua đúng {@link ConfirmTransferHandler} — cùng
 * phép đối chiếu số tiền, cùng dòng nhật ký, cùng lời gọi sang Ordering. Khác biệt duy nhất là
 * <i>nguồn</i> của dữ liệu, và nguồn này còn chắc hơn: ta tự hỏi payOS bằng credential của mình chứ
 * không nhận một payload từ internet.
 */
@Service
public class ReconcileIntentHandler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileIntentHandler.class);
    private static final String PROVIDER = "PAYOS";

    private final PaymentIntentRepository intents;
    private final PayosGateway payos;
    private final ConfirmTransferHandler confirmTransfer;

    public ReconcileIntentHandler(
            PaymentIntentRepository intents, PayosGateway payos, ConfirmTransferHandler confirmTransfer) {
        this.intents = intents;
        this.payos = payos;
        this.confirmTransfer = confirmTransfer;
    }

    public ConfirmTransferHandler.Result handle(UUID orderId) {
        PaymentIntent intent = intents.findByOrder(orderId)
                .orElseThrow(() -> new ApiException(
                        PaymentErrorCode.PAYMENT_INTENT_NOT_FOUND, "No payment intent for this order"));

        if (intent.isConfirmed()) {
            return new ConfirmTransferHandler.Result(
                    "DUPLICATE", true, "Đơn đã được ghi nhận trả tiền lúc " + intent.confirmedAt());
        }

        Optional<PayosGateway.Settlement> found = fetch(intent);
        if (found.isEmpty()) {
            return new ConfirmTransferHandler.Result("UNKNOWN_REFERENCE", true, "payOS không biết link này");
        }

        PayosGateway.Settlement settlement = found.get();
        if (!settlement.isPaid()) {
            return new ConfirmTransferHandler.Result(
                    "NOT_PAID", true, "payOS đang ở trạng thái " + settlement.status());
        }
        if (settlement.transactionReference() == null) {
            // payOS nói PAID mà không đưa mã giao dịch nào. Không bịa ra một mã thay thế: mã giao dịch
            // là khoá chống ghi nhận trùng, và một khoá bịa sẽ khiến lần chuyển tiền thật sau đó bị coi
            // là trùng lặp — tức là một khoản tiền thứ hai biến mất không dấu vết.
            log.error(
                    "payOS báo link {} của đơn {} đã PAID nhưng không có mã giao dịch — cần đối soát tay",
                    intent.payosOrderCode(),
                    orderId);
            return new ConfirmTransferHandler.Result(
                    "NOT_PAID", true, "payOS báo PAID nhưng không có mã giao dịch để ghi nhận");
        }

        log.info(
                "Đối soát đơn {}: payOS báo đã thu {}đ qua giao dịch {}",
                orderId,
                settlement.amountPaidVnd(),
                settlement.transactionReference());

        return confirmTransfer.handle(new ConfirmTransferHandler.Command(
                PROVIDER,
                settlement.transactionReference(),
                intent.payosOrderCode(),
                settlement.amountPaidVnd(),
                rawJson(settlement)));
    }

    private Optional<PayosGateway.Settlement> fetch(PaymentIntent intent) {
        try {
            return payos.fetchSettlement(intent.payosOrderCode());
        } catch (PayosGateway.Unavailable e) {
            log.warn("payOS không phản hồi khi đối soát đơn {}", intent.orderId(), e);
            throw new ApiException(PaymentErrorCode.PAYOS_UNAVAILABLE, "payOS unavailable, please retry");
        }
    }

    /** Payload nguyên văn cho nhật ký: nói rõ đây là đường đối soát, không phải một webhook nhận được. */
    private static String rawJson(PayosGateway.Settlement settlement) {
        return "{\"source\":\"reconcile\",\"status\":\"%s\",\"amountPaid\":%d,\"reference\":\"%s\"}"
                .formatted(settlement.status(), settlement.amountPaidVnd(), settlement.transactionReference());
    }
}
