// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import com.nexaticket.payment.application.command.HandlePayosWebhookHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhận báo có từ payOS.
 *
 * <p>Đường dẫn này <b>công khai</b> — {@code SecurityAutoConfiguration} mở sẵn
 * {@code /api/billing/bank/webhook/**} vì payOS không có JWT của ta. Lớp bảo vệ duy nhất là chữ ký
 * HMAC-SHA256 trên payload, và nó được kiểm ở {@link HandlePayosWebhookHandler}.
 *
 * <p>Lớp này cố ý <b>mỏng đến mức không có quyết định nào</b>. Nó nhận body dạng {@code String} chứ không
 * phải một record đã map sẵn, và lý do là bảo mật chứ không phải tiện: chữ ký tính trên đúng những byte
 * payOS gửi, nên một vòng deserialize rồi serialize lại có thể đổi cách số và chuỗi được viết ra và làm
 * chữ ký lệch vì một lý do không ai nghĩ tới. Chuỗi thô cũng là thứ phải vào {@code bank_webhook_log}
 * nguyên văn.
 *
 * <p>Hai mã trả về, và khác biệt giữa chúng quan trọng:
 *
 * <ul>
 *   <li><b>401</b> khi không xác thực được. Buộc payOS giao lại, và giữ cho một lần cấu hình lệch checksum
 *       key không âm thầm nuốt mọi khoản tiền vào.
 *   <li><b>200</b> cho mọi thứ đã xử lý xong, kể cả khi từ chối vì lý do nghiệp vụ. payOS giao lại cho tới
 *       khi nhận 2xx, và giao lại một message sẽ luôn bị từ chối chỉ làm ngập log — việc cần làm là một con
 *       người nhìn vào {@code bank_webhook_log}.
 * </ul>
 *
 * <p>Còn <b>5xx</b> không đến từ đây mà từ {@code ApiException} bay lên khi Ordering không phản hồi. Đó là
 * lỗi tạm thời, và là lúc duy nhất việc giao lại thật sự có ích.
 */
@RestController
@RequestMapping("/api/billing/bank/webhook")
public class PayosWebhookController {

    private final HandlePayosWebhookHandler handler;

    public PayosWebhookController(HandlePayosWebhookHandler handler) {
        this.handler = handler;
    }

    @PostMapping(path = "/payos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> onPayment(@RequestBody String rawBody) {
        HandlePayosWebhookHandler.Outcome outcome = handler.handle(rawBody);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome.outcome());
        if (outcome.note() != null) {
            body.put("note", outcome.note());
        }
        // Không nói lý do chi tiết khi chưa xác thực được: một thông báo tường minh chỉ giúp người đang dò
        // khoá. Lý do đã nằm đầy đủ trong log và trong bank_webhook_log.
        return outcome.authentic()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(401).body(Map.of("outcome", outcome.outcome()));
    }
}
