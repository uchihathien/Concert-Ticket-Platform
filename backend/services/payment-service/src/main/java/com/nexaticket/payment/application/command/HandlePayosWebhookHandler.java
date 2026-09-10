// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.payment.domain.port.WebhookLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Xử lý một webhook payOS: kiểm chữ ký, ghi nhật ký, rồi đối chiếu tiền.
 *
 * <p>Tồn tại như một lớp riêng vì nó quyết định <b>hai</b> thứ mà một controller không nên quyết: webhook
 * này có đáng tin không, và nếu không thì phải để lại vết gì. Controller chỉ còn việc dịch kết quả thành
 * mã HTTP.
 *
 * <p>Quy tắc trả lời payOS — toàn bộ nằm ở {@link Outcome#authentic}:
 *
 * <ul>
 *   <li><b>Không xác thực được thì KHÔNG trả 2xx.</b> Nghe phản trực giác, vì mọi nhánh từ chối vì lý do
 *       nghiệp vụ đều trả 200. Lý do rất cụ thể: trả 2xx cho một chữ ký sai nghĩa là hôm nào checksum key
 *       bị cấu hình lệch, payOS sẽ thôi giao lại và <b>mọi khoản tiền vào đều im lặng biến mất</b>. Một
 *       lỗi cấu hình phải kêu to và phải được retry, không được tự lành.
 *   <li><b>Xác thực được thì LUÔN trả 2xx</b>, kể cả khi từ chối — trừ khi Ordering không phản hồi, và đó
 *       là lúc duy nhất việc giao lại thật sự có ích.
 * </ul>
 *
 * <p>Một webhook đã ký mà báo <b>không thành công</b>, hoặc thiếu trường, vẫn được ghi nhật ký rồi trả
 * 200: không có tiền nào vào nên không có gì để xác nhận, và giao lại cũng không làm nó thành công.
 *
 * <p><b>Webhook thử lúc đăng ký.</b> payOS gọi endpoint này ngay khi ta đăng ký URL, với một payload giả
 * ({@code orderCode} 123). Nó <b>phải</b> nhận được 2xx, nếu không việc đăng ký thất bại và sau đó không
 * có webhook thật nào tới cả. Đường đó đi vào nhánh {@code UNKNOWN_REFERENCE} của
 * {@link ConfirmTransferHandler} và trả 200 — đúng như cần.
 */
@Service
public class HandlePayosWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(HandlePayosWebhookHandler.class);
    private static final String PROVIDER = "PAYOS";

    private final PayosGateway payos;
    private final ConfirmTransferHandler confirmTransfer;
    private final WebhookLog webhookLog;

    public HandlePayosWebhookHandler(
            PayosGateway payos, ConfirmTransferHandler confirmTransfer, WebhookLog webhookLog) {
        this.payos = payos;
        this.confirmTransfer = confirmTransfer;
        this.webhookLog = webhookLog;
    }

    /**
     * @param authentic false nghĩa là chữ ký không qua được cửa — người gọi phải trả <b>401</b>, không 200
     * @param outcome một trong các giá trị của {@code ck_webhook_outcome}
     */
    public record Outcome(boolean authentic, String outcome, String note) {}

    public Outcome handle(String rawBody) {
        PayosGateway.WebhookVerification verification = payos.verifyWebhook(rawBody);

        if (!verification.isAuthentic()) {
            // Ghi vào nhật ký kể cả khi bị chặn ở cửa, và đây là thay đổi có chủ đích so với webhook SePay
            // trước đây (chỗ đó cố ý KHÔNG ghi request không qua cửa). Với payOS, một chữ ký sai là tín
            // hiệu mạnh: hoặc có người đang giả webhook, hoặc cấu hình đang lệch. Cả hai đều cần một chuỗi
            // thời gian để nhìn ra, và chuỗi đó chỉ có nếu ta ghi lại.
            //
            // Payload thô KHÔNG vào cột jsonb ở nhánh này: nó có thể không phải JSON, và một câu INSERT vỡ
            // sẽ xoá luôn cả dòng nhật ký. Độ dài là đủ để biết có một cái gì đã tới.
            String note = verification.rejectionReason();
            webhookLog.record(WebhookLog.Entry.rejected(
                    PROVIDER, null, note + " (body " + (rawBody == null ? 0 : rawBody.length()) + " byte)"));
            return new Outcome(false, "REJECTED", note);
        }

        PayosGateway.WebhookPayment payment = verification.payment();

        if (!payment.successful()) {
            String note = "payOS báo giao dịch không thành công: [%s] %s"
                    .formatted(payment.providerCode(), payment.providerDesc());
            return logAndAccept(payment, rawBody, note);
        }
        if (!payment.isComplete()) {
            // Không bịa ra mã giao dịch thay thế khi thiếu: nó là khoá chống ghi nhận trùng, và một khoá
            // bịa sẽ khiến lần chuyển tiền THẬT sau đó bị coi là trùng lặp — tức là một khoản tiền thứ hai
            // biến mất không dấu vết.
            log.warn(
                    "Webhook payOS thiếu trường bắt buộc (orderCode={}, amount={}, txnRef={})",
                    payment.orderCode(),
                    payment.amountVnd(),
                    payment.transactionReference());
            return logAndAccept(payment, rawBody, "Thiếu orderCode, amount hoặc mã giao dịch");
        }

        ConfirmTransferHandler.Result result = confirmTransfer.handle(new ConfirmTransferHandler.Command(
                PROVIDER, payment.transactionReference(), payment.orderCode(), payment.amountVnd(), rawBody));

        return new Outcome(true, result.outcome(), result.note());
    }

    /** Ghi vết một webhook đã ký nhưng không có gì để xác nhận, rồi nhận — giao lại cũng không đổi được gì. */
    private Outcome logAndAccept(PayosGateway.WebhookPayment payment, String rawBody, String note) {
        log.info("Bỏ qua webhook payOS của orderCode {}: {}", payment.orderCode(), note);
        webhookLog.record(new WebhookLog.Entry(
                PROVIDER,
                payment.transactionReference(),
                payment.orderCode() == 0 ? null : payment.orderCode(),
                null,
                payment.amountVnd() == 0 ? null : payment.amountVnd(),
                rawBody,
                "REJECTED",
                note));
        return new Outcome(true, "REJECTED", note);
    }
}
