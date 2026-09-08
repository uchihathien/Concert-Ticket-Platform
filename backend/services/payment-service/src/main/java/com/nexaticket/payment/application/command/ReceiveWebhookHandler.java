// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.port.BankTransferTranslator;
import com.nexaticket.payment.domain.port.OrderingPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Cửa vào của webhook ở tầng application.
 *
 * <p>Tồn tại để controller <b>không</b> phải chạm vào domain: nó chỉ nhận {@code Map} thô và nhận
 * lại một {@link Receipt} gồm mã HTTP và tên kết luận. Nhờ vậy chiều phụ thuộc hexagonal được giữ
 * (interfaces → application → domain), và toàn bộ quyết định "mã nào ứng với kết luận nào" nằm ở
 * một chỗ thay vì rải trong controller.
 */
@Service
public class ReceiveWebhookHandler {

    private final BankTransferTranslator translator;
    private final HandleBankTransferHandler processor;

    public ReceiveWebhookHandler(BankTransferTranslator translator, HandleBankTransferHandler processor) {
        this.translator = translator;
        this.processor = processor;
    }

    /**
     * Mã HTTP quyết định hành vi retry của nhà cung cấp, nên nó là quyết định nghiệp vụ:
     *
     * <ul>
     *   <li><b>2xx</b> — đã xử lý xong, kể cả khi kết luận là {@code MANUAL_REVIEW}: giao dịch đã
     *       vào danh sách đối soát, việc còn lại là của con người chứ không phải của SePay.
     *   <li><b>4xx</b> — đừng gửi lại; gửi lại cũng vậy.
     *   <li><b>5xx</b> — ta hỏng, xin gửi lại.
     * </ul>
     *
     * @param outcome tên kết luận, đủ để log và trả về; controller không cần biết enum của domain
     */
    public record Receipt(int httpStatus, String outcome) {}

    public Receipt handle(Map<String, Object> payload) {
        BankTransfer transfer;
        try {
            transfer = translator.translate(payload);
        } catch (BankTransferTranslator.MalformedPayloadException e) {
            return new Receipt(
                    PaymentErrorCode.WEBHOOK_MALFORMED.httpStatus(), PaymentErrorCode.WEBHOOK_MALFORMED.code());
        }

        try {
            return new Receipt(
                    200, processor.handle(transfer, sha256(payload.toString())).name());
        } catch (OrderingPort.OrderingUnavailableException e) {
            // 503 để nhà cung cấp gửi lại. Transaction đã rollback nên dòng chống trùng cũng
            // mất, và lần gửi lại được xử lý từ đầu như chưa từng thấy.
            return new Receipt(503, "RETRY_LATER");
        }
    }

    /** Dấu vân tay payload, để phát hiện cùng một mã giao dịch gửi kèm nội dung khác. */
    private static String sha256(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }
}
