// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.OrderingPort;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.WebhookLog;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi nhận một lần payOS báo có, rồi báo sang Ordering.
 *
 * <p>Đây là chỗ tiền thật gặp phần mềm, nên thứ tự các bước là cố ý và không được đổi:
 *
 * <ol>
 *   <li><b>Ghi nhật ký ở mọi nhánh.</b> Kể cả khi từ chối. Một giao dịch bị từ chối mà không để lại vết
 *       là một khách gọi lên tổng đài mà không ai trả lời được.
 *   <li><b>Tra intent theo {@code orderCode}.</b> Đây là thay đổi lớn nhất so với thời SePay: payOS trả
 *       về đúng con số ta đã cấp khi tạo link, trong một payload đã ký. Không còn phải rút mã đơn ra
 *       khỏi một câu chữ do khách gõ, nên cũng không còn nhánh "khách gõ thiếu một ký tự".
 *   <li><b>Đối chiếu số tiền ở domain.</b> Không bao giờ tin số tiền trong webhook là số phải trả; nó
 *       chỉ là số đã trả. Trả thiếu thì không phát vé.
 *   <li><b>Báo sang Ordering trong cùng transaction.</b> Gọi hỏng thì ném ra, transaction rollback, ta
 *       trả 5xx, và payOS giao lại webhook. Retry của nhà cung cấp là cơ chế thử lại tốt hơn bất cứ thứ
 *       gì ta tự viết — điều kiện là đừng nuốt lỗi.
 * </ol>
 *
 * <p>Dùng chung cho <b>ba</b> nguồn: webhook payOS, lệnh đối soát kéo trạng thái về
 * ({@link ReconcileIntentHandler}), và chuyển khoản giả lập ở sandbox
 * ({@link SimulateTransferHandler}). Cả ba đi qua đúng một đường — cùng phép đối chiếu số tiền, cùng
 * dòng nhật ký, cùng lời gọi sang Ordering. Một nguồn tự viết đường tắt riêng là một nguồn sẽ phát vé
 * theo luật khác.
 */
@Service
public class ConfirmTransferHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfirmTransferHandler.class);

    private final PaymentIntentRepository intents;
    private final OrderingPort ordering;
    private final WebhookLog webhookLog;
    private final Clock clock;

    public ConfirmTransferHandler(
            PaymentIntentRepository intents, OrderingPort ordering, WebhookLog webhookLog, Clock clock) {
        this.intents = intents;
        this.ordering = ordering;
        this.webhookLog = webhookLog;
        this.clock = clock;
    }

    /**
     * @param provider tên nguồn xác nhận; {@code SANDBOX} nghĩa là tiền giả
     * @param providerTxnId mã giao dịch phía ngân hàng — khoá chống ghi nhận trùng
     * @param payosOrderCode mã link payOS; khoá để tìm ra đơn
     * @param amountVnd số tiền payOS báo về
     * @param rawJson payload nguyên văn, để ghi nhật ký
     */
    public record Command(String provider, String providerTxnId, long payosOrderCode, long amountVnd, String rawJson) {}

    /**
     * @param accepted true nghĩa là "thôi giao lại" — gồm cả trường hợp từ chối có lý do rõ ràng. Chỉ
     *     những lỗi tạm thời mới được để cho payOS giao lại.
     */
    public record Result(String outcome, boolean accepted, String note) {}

    @Transactional
    public Result handle(Command cmd) {
        Optional<PaymentIntent> found = intents.findByPayosOrderCode(cmd.payosOrderCode());
        if (found.isEmpty()) {
            // Không phải chuyện lạ và không phải lỗi: payOS gửi một webhook THỬ với orderCode giả (123)
            // ngay lúc đăng ký URL, và nó phải nhận được 2xx nếu không việc đăng ký thất bại.
            return reject(cmd, null, "UNKNOWN_REFERENCE", "Không có yêu cầu thanh toán nào mang orderCode này");
        }

        PaymentIntent intent = found.get();
        String referenceValue = intent.reference().value();

        // Cùng một mã giao dịch nhưng đã ghi nhận cho ĐƠN KHÁC. Không bao giờ là chuyện bình thường:
        // hoặc nhà cung cấp phát lại mã, hoặc ai đó đang phát lại một webhook cũ đã bị sửa. Chặn ở đây
        // thay vì để unique index nổ, vì một ràng buộc vi phạm sẽ làm hỏng transaction và cuốn theo cả
        // dòng nhật ký cần ghi.
        UUID owner =
                intents.orderOfTransaction(cmd.provider(), cmd.providerTxnId()).orElse(null);
        if (owner != null && !owner.equals(intent.orderId())) {
            log.error(
                    "Giao dịch {} của {} đã ghi nhận cho đơn {}, nay lại khớp đơn {} — cần đối soát tay",
                    cmd.providerTxnId(),
                    cmd.provider(),
                    owner,
                    intent.orderId());
            return reject(cmd, referenceValue, "DUPLICATE", "Mã giao dịch đã dùng cho đơn " + owner);
        }

        PaymentIntent.Settlement settlement =
                intent.confirm(cmd.provider(), cmd.providerTxnId(), cmd.amountVnd(), clock.instant());

        return switch (settlement) {
            case CONFIRMED -> confirmAndNotify(cmd, intent, referenceValue);
            case DUPLICATE -> {
                webhookLog.record(entry(cmd, referenceValue, "DUPLICATE", null));
                yield new Result("DUPLICATE", true, "Giao dịch này đã được ghi nhận trước đó");
            }
            case ALREADY_CONFIRMED -> reject(
                    cmd,
                    referenceValue,
                    "DUPLICATE",
                    "Đơn đã nhận tiền từ giao dịch " + intent.providerTxnId() + " — cần đối soát tay");
            case NOT_PENDING -> reject(
                    cmd, referenceValue, "NOT_PENDING", "Yêu cầu thanh toán đã đóng, trạng thái " + intent.status());
            case AMOUNT_MISMATCH -> reject(
                    cmd,
                    referenceValue,
                    "AMOUNT_MISMATCH",
                    "Chuyển " + cmd.amountVnd() + "đ nhưng đơn cần " + intent.amountVnd() + "đ");
        };
    }

    private Result confirmAndNotify(Command cmd, PaymentIntent intent, String referenceValue) {
        if (!intents.updateStatusIfPending(intent)) {
            // Ai đó đã đóng intent giữa lúc ta đọc và lúc ta ghi — thường là job quét hết hạn. Câu
            // UPDATE có điều kiện đã chặn việc ghi đè; ở đây chỉ còn việc nói rõ ra. KHÔNG phát vé,
            // nhưng đây là tiền THẬT đã vào một đơn đã đóng, nên nó phải nằm trong nhật ký ở mức ERROR.
            log.error(
                    "Đơn {} không còn PENDING lúc ghi xác nhận (giao dịch {} của {}, {}đ) — cần đối soát tay",
                    intent.orderId(),
                    cmd.providerTxnId(),
                    cmd.provider(),
                    cmd.amountVnd());
            return reject(cmd, referenceValue, "NOT_PENDING", "Yêu cầu thanh toán bị đóng ngay trước khi ghi nhận");
        }
        try {
            OrderingPort.Confirmation confirmation = ordering.confirmPayment(intent.orderId());
            if (confirmation == OrderingPort.Confirmation.MANUAL_REVIEW) {
                // Tiền có thật và đã ghi nhận — nên nhật ký vẫn là CONFIRMED, không phải một nhánh
                // từ chối. Cái khác là khách sẽ KHÔNG có vé: đơn đã đóng và ghế đã nhả trước khi
                // webhook tới. Ghi note để người đối soát tìm ra bằng một câu SQL, và log ERROR vì
                // đây là khoản tiền duy nhất trong cả luồng cần người can thiệp.
                String note = "Đơn đã đóng trước khi tiền vào — Ordering chuyển sang MANUAL_REVIEW";
                webhookLog.record(entry(cmd, referenceValue, "CONFIRMED", note));
                log.error(
                        "ĐỐI SOÁT TAY: đơn {} nhận {}đ (giao dịch {} của {}) sau khi đã đóng",
                        intent.orderId(),
                        cmd.amountVnd(),
                        cmd.providerTxnId(),
                        cmd.provider());
                return new Result("CONFIRMED", true, note);
            }
            webhookLog.record(entry(cmd, referenceValue, "CONFIRMED", null));
        } catch (OrderingPort.OrderingUnavailableException e) {
            // Ném ra để transaction rollback: intent quay về PENDING và nhật ký cũng mất dòng vừa ghi.
            // Nghe có vẻ tiếc, nhưng phương án còn lại tệ hơn nhiều — một intent CONFIRMED mà Ordering
            // không biết là một khách đã trả tiền và vĩnh viễn không có vé.
            log.error("Không báo được sang Ordering cho đơn {}, để payOS giao lại", intent.orderId(), e);
            throw new ApiException(
                    PaymentErrorCode.ORDERING_UNAVAILABLE, "Ordering unavailable, webhook will be retried");
        }
        return new Result("CONFIRMED", true, null);
    }

    private Result reject(Command cmd, String referenceValue, String outcome, String note) {
        webhookLog.record(entry(cmd, referenceValue, outcome, note));
        // accepted = true: đây là những lý do sẽ KHÔNG khác đi ở lần giao lại thứ hai mươi. Trả 4xx chỉ
        // khiến nhà cung cấp giao lại vô ích và làm ngập log — việc cần làm là một con người nhìn vào
        // bank_webhook_log, không phải một lần retry nữa.
        log.warn(
                "Từ chối webhook {} (orderCode {}): {} ({})", cmd.providerTxnId(), cmd.payosOrderCode(), outcome, note);
        return new Result(outcome, true, note);
    }

    private static WebhookLog.Entry entry(Command cmd, String referenceValue, String outcome, String note) {
        return new WebhookLog.Entry(
                cmd.provider(),
                cmd.providerTxnId(),
                cmd.payosOrderCode(),
                referenceValue,
                cmd.amountVnd(),
                cmd.rawJson(),
                outcome,
                note);
    }
}
