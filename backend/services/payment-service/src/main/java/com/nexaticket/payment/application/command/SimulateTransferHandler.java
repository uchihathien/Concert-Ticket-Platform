// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Giả lập khách đã trả tiền — <b>chỉ chạy ở chế độ sandbox</b>.
 *
 * <p>Đường này <b>quan trọng hơn</b> sau khi chuyển sang payOS, không phải ít hơn: payOS
 * <b>không có môi trường test</b>. Tài liệu của họ nói thẳng là mọi thử nghiệm diễn ra trên production
 * với số tiền nhỏ, bằng tài khoản ngân hàng thật. Không có đường giả lập thì mỗi lần chạy thử luồng mua
 * vé trong lúc phát triển đều tốn tiền thật và cần một chiếc điện thoại.
 *
 * <p>Điều quan trọng nhất về nó không phải là nó giả, mà là <b>nó đi qua đúng cùng một đường với webhook
 * thật</b>: cùng {@link ConfirmTransferHandler}, cùng cách tra intent theo {@code orderCode}, cùng phép
 * đối chiếu số tiền, cùng dòng nhật ký, cùng lời gọi sang Ordering. Thứ duy nhất khác là <i>nguồn</i>
 * của message. Một sandbox tự viết một đường tắt riêng sẽ cho cảm giác đã chạy được, rồi vỡ ở đúng ngày
 * lên thật.
 *
 * <p>Muốn thử với <b>tiền thật</b> mà không chờ webhook (vì ở máy phát triển webhook không tới được) thì
 * dùng {@link ReconcileIntentHandler} — nó kéo trạng thái thật từ payOS về.
 */
@Service
public class SimulateTransferHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulateTransferHandler.class);
    private static final String PROVIDER = "SANDBOX";

    private final PaymentIntentRepository intents;
    private final ConfirmTransferHandler confirmTransfer;
    private final PaymentProperties properties;

    public SimulateTransferHandler(
            PaymentIntentRepository intents, ConfirmTransferHandler confirmTransfer, PaymentProperties properties) {
        this.intents = intents;
        this.confirmTransfer = confirmTransfer;
        this.properties = properties;
    }

    /**
     * @param amountVnd số tiền giả lập; null nghĩa là trả đủ. Cho phép truyền số khác vì chuyển thiếu
     *     tiền là tình huống có thật và phải thử được — đó là nhánh duy nhất chặn giữa "đã chuyển
     *     10.000đ" và "được phát vé 3.000.000đ".
     * @param providerTxnId mã giao dịch giả lập; truyền lại đúng mã cũ để thử đường webhook trùng
     */
    public record Command(UUID orderId, Long amountVnd, String providerTxnId) {}

    public ConfirmTransferHandler.Result handle(Command cmd) {
        if (!properties.sandbox()) {
            throw new ApiException(
                    PaymentErrorCode.SANDBOX_DISABLED, "Sandbox transfers are disabled on this environment");
        }

        PaymentIntent intent = intents.findByOrder(cmd.orderId())
                .orElseThrow(() -> new ApiException(
                        PaymentErrorCode.PAYMENT_INTENT_NOT_FOUND, "No payment intent for this order"));

        String txnId = cmd.providerTxnId() == null || cmd.providerTxnId().isBlank()
                ? "SBX-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(Locale.ROOT)
                : cmd.providerTxnId();
        long amount = cmd.amountVnd() == null ? intent.amountVnd() : cmd.amountVnd();

        log.warn("SANDBOX: giả lập trả {}đ cho đơn {} — đây KHÔNG phải tiền thật", amount, cmd.orderId());

        return confirmTransfer.handle(new ConfirmTransferHandler.Command(
                PROVIDER, txnId, intent.payosOrderCode(), amount, rawJson(cmd.orderId(), intent, amount)));
    }

    private static String rawJson(UUID orderId, PaymentIntent intent, long amount) {
        return "{\"simulated\":true,\"orderId\":\"%s\",\"orderCode\":%d,\"amount\":%d}"
                .formatted(orderId, intent.payosOrderCode(), amount);
    }
}
