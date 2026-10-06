// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import com.nexaticket.payment.application.command.CancelIntentHandler;
import com.nexaticket.payment.application.command.ConfirmTransferHandler;
import com.nexaticket.payment.application.command.OpenIntentHandler;
import com.nexaticket.payment.application.command.ReconcileIntentHandler;
import com.nexaticket.payment.application.command.SimulateTransferHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service cho saga checkout — <b>chỉ service nội bộ gọi được</b>.
 *
 * <p>Gateway không route {@code /internal/**} ra ngoài. Nói thẳng một điều đang đúng ở thời điểm viết:
 * <b>chưa có xác thực nội bộ ở tầng service</b>, nên lớp bảo vệ duy nhất là mạng. Với
 * {@code simulate-transfer} bên dưới thì điều đó đặc biệt nghiêm trọng — nó đánh dấu một đơn là đã trả
 * tiền, và cờ {@code nexaticket.payment.sandbox} là thứ duy nhất chặn nó ở production.
 *
 * <p>Mọi endpoint ở đây đều idempotent theo {@code orderId}: saga có thể chạy lại bất kỳ bước nào sau
 * timeout mạng, và bước bù trừ có thể chạy nhiều lần.
 */
@RestController
@RequestMapping("/internal/payment-intents")
public class InternalPaymentController {

    private final OpenIntentHandler openIntent;
    private final CancelIntentHandler cancelIntent;
    private final SimulateTransferHandler simulateTransfer;
    private final ReconcileIntentHandler reconcileIntent;

    public InternalPaymentController(
            OpenIntentHandler openIntent,
            CancelIntentHandler cancelIntent,
            SimulateTransferHandler simulateTransfer,
            ReconcileIntentHandler reconcileIntent) {
        this.openIntent = openIntent;
        this.cancelIntent = cancelIntent;
        this.simulateTransfer = simulateTransfer;
        this.reconcileIntent = reconcileIntent;
    }

    public record OpenIntentRequest(
            @NotNull UUID orderId,
            @NotNull UUID organizationId,
            @Positive long amountVnd,
            @NotNull Instant expiresAt) {}

    /**
     * Hình dạng JSON là <b>hợp đồng với ordering-service</b>: tên field ở đây phải khớp
     * {@code PaymentHttpAdapter.IntentResponse}. Viết tường minh thay vì trả thẳng record của tầng
     * application, để một lần đổi tên bên trong không âm thầm làm hỏng checkout ở runtime.
     *
     * @param vietQrPayload chuỗi EMVCo payOS sinh, trỏ về tài khoản ảo của riêng link này
     * @param checkoutUrl trang thanh toán payOS host — đường chính cho khách; QR là đường phụ cho người
     *     muốn tự quét bằng app ngân hàng
     */
    public record IntentResponse(
            String paymentReference,
            String vietQrPayload,
            String checkoutUrl,
            String bankBin,
            String bankAccountNumber,
            String bankAccountName) {}

    @PostMapping
    public IntentResponse open(@Valid @RequestBody OpenIntentRequest request) {
        OpenIntentHandler.Result result = openIntent.handle(new OpenIntentHandler.Command(
                request.orderId(), request.organizationId(), request.amountVnd(), request.expiresAt()));

        return new IntentResponse(
                result.paymentReference(),
                result.vietQrPayload(),
                result.checkoutUrl(),
                result.bankBin(),
                result.bankAccountNumber(),
                result.bankAccountName());
    }

    /** Bù trừ khi saga hỏng. Không có intent nào cũng trả 204 — bù trừ một việc chưa xảy ra là vô hại. */
    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID orderId) {
        cancelIntent.handle(orderId);
    }

    /**
     * Kéo trạng thái link về từ payOS, thay vì chờ webhook đẩy sang.
     *
     * <p>Đây là <b>đường sửa chữa</b>, và nó cần thiết vì hai chuyện rất cụ thể: webhook payOS đòi một URL
     * HTTPS gọi được từ internet nên ở máy phát triển nó không tới, và payOS
     * <b>không có môi trường sandbox</b> nên mọi lần thử là tiền thật. Không có endpoint này thì mỗi lần
     * thử luồng thanh toán kết thúc bằng một đơn đã trả tiền mà không có vé, và cách sửa là SQL tay.
     *
     * <p>An toàn để gọi bất cứ lúc nào: nó đi qua đúng {@link ConfirmTransferHandler} như webhook thật, nên
     * không có đường nào ở đây ghi nhận tiền theo luật khác. Gọi hai lần chỉ trả về {@code DUPLICATE}.
     */
    @PostMapping("/{orderId}/reconcile")
    public SettlementResponse reconcile(@PathVariable UUID orderId) {
        ConfirmTransferHandler.Result result = reconcileIntent.handle(orderId);
        return new SettlementResponse(result.outcome(), result.note());
    }

    /**
     * Giả lập khách đã trả tiền. Chỉ chạy khi {@code nexaticket.payment.sandbox=true}.
     *
     * <p>Khác {@code reconcile} ở một điểm duy nhất nhưng quan trọng: ở đây <b>không có đồng nào vào thật</b>.
     * Dùng nó để chạy trọn luồng mua vé khi phát triển offline; dùng {@code reconcile} khi đã chuyển tiền
     * thật mà webhook không tới được.
     *
     * @param request có thể vắng mặt hoàn toàn; khi đó là "trả đủ, mã giao dịch tự sinh"
     */
    @PostMapping("/{orderId}/simulate-transfer")
    public SettlementResponse simulateTransfer(
            @PathVariable UUID orderId, @RequestBody(required = false) SimulateRequest request) {

        SimulateRequest body = request == null ? new SimulateRequest(null, null) : request;
        ConfirmTransferHandler.Result result = simulateTransfer.handle(
                new SimulateTransferHandler.Command(orderId, body.amountVnd(), body.providerTxnId()));

        return new SettlementResponse(result.outcome(), result.note());
    }

    public record SimulateRequest(Long amountVnd, String providerTxnId) {}

    public record SettlementResponse(String outcome, String note) {}
}
