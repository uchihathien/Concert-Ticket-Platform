// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.command.ReceiveWebhookHandler;
import com.nexaticket.payment.infrastructure.sepay.SePayProperties;
import com.nexaticket.platform.security.annotation.PublicEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook ngân hàng của SePay.
 *
 * <p>Đường riêng, cố ý nằm ngoài mọi thứ áp cho API của khách: không qua {@code IdempotencyFilter}
 * (nhà cung cấp không gửi {@code Idempotency-Key}, ta tự chống trùng bằng mã giao dịch của họ),
 * không qua {@code TenantFilter} (không có người dùng nào ở đây), không session, không CSRF.
 *
 * <p>Controller mỏng có chủ ý: nó chỉ xác thực rồi chuyển tiếp. Mọi quyết định về tiền — kể cả
 * "mã HTTP nào ứng với kết luận nào" — nằm ở tầng application, nơi test được mà không cần dựng web.
 */
@RestController
@RequestMapping("/api/billing/bank/webhook")
public class SePayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SePayWebhookController.class);

    private final ReceiveWebhookHandler receiveWebhook;
    private final SePayProperties properties;

    public SePayWebhookController(ReceiveWebhookHandler receiveWebhook, SePayProperties properties) {
        this.receiveWebhook = receiveWebhook;
        this.properties = properties;
    }

    @PostMapping("/sepay")
    @PublicEndpoint(reason = "Ngân hàng gọi vào, không có người dùng nào; xác thực bằng khoá SePay")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> payload) {

        if (!isAuthorized(authorization)) {
            // 401 và KHÔNG ghi gì: payload chưa được tin thì cũng không đáng lưu.
            log.warn("Webhook SePay bị từ chối vì sai khoá");
            return ResponseEntity.status(PaymentErrorCode.WEBHOOK_UNAUTHORIZED.httpStatus())
                    .body(Map.of("code", PaymentErrorCode.WEBHOOK_UNAUTHORIZED.code()));
        }

        ReceiveWebhookHandler.Receipt receipt = receiveWebhook.handle(payload);
        return ResponseEntity.status(receipt.httpStatus()).body(Map.of("outcome", receipt.outcome()));
    }

    /**
     * So sánh khoá bằng thời gian hằng số.
     *
     * <p>{@code String.equals} thoát ra ngay ở byte đầu tiên khác nhau, nên thời gian phản hồi rò
     * rỉ độ dài tiền tố đúng — đủ để dò ra khoá bằng nhiều lần thử. Đây là endpoint công khai
     * trên internet, nên khác biệt đó là thật chứ không phải lý thuyết.
     *
     * <p>Khoá rỗng thì từ chối tất cả: thiếu cấu hình phải làm webhook ngừng chạy, chứ không phải
     * làm nó chấp nhận mọi request.
     */
    private boolean isAuthorized(String authorization) {
        if (authorization == null
                || properties.apiKey() == null
                || properties.apiKey().isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),
                ("Apikey " + properties.apiKey()).getBytes(StandardCharsets.UTF_8));
    }
}
