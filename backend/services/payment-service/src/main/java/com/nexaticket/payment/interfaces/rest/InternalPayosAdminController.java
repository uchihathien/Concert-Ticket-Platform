// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import com.nexaticket.payment.application.command.RegisterPayosWebhookHandler;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lệnh vận hành với payOS — <b>chỉ gọi được trong mạng nội bộ</b>.
 *
 * <p>Chỉ có một việc: đăng ký URL webhook. Và nó là một <b>lệnh tường minh</b> chứ không phải một bước
 * trong lúc khởi động, vì ba lý do:
 *
 * <ul>
 *   <li>Nó <b>ghi cấu hình ở phía nhà cung cấp</b>, không ở phía ta. Một service tự làm việc đó mỗi lần
 *       khởi động sẽ có ngày trỏ webhook của production về một tunnel ngrok trên máy ai đó — và triệu chứng
 *       là mọi khoản tiền vào production đều im lặng không được ghi nhận.
 *   <li>payOS gọi thử endpoint webhook ngay trong lời gọi này. Chạy lúc khởi động nghĩa là gọi thử vào một
 *       service chưa nhận request được, và việc đăng ký thất bại với một lý do gây hiểu nhầm.
 *   <li>Nó cần chạy <b>một lần</b> cho mỗi môi trường, không phải mỗi lần deploy.
 * </ul>
 */
@RestController
@RequestMapping("/internal/payos")
public class InternalPayosAdminController {

    private final RegisterPayosWebhookHandler registerWebhook;

    public InternalPayosAdminController(RegisterPayosWebhookHandler registerWebhook) {
        this.registerWebhook = registerWebhook;
    }

    /**
     * @param webhookUrl để trống thì dùng {@code nexaticket.payment.payos.webhook-url} trong cấu hình.
     *     Truyền tường minh khi cần trỏ tạm về một tunnel lúc phát triển — nhưng đừng làm vậy ở production.
     */
    public record RegisterRequest(String webhookUrl) {}

    /**
     * @param accountNumber tài khoản payOS đã nối với kênh. Nhìn vào đây để biết ta vừa đăng ký
     *     <b>đúng kênh thanh toán</b> hay không — một credential lẫn sang kênh khác vẫn đăng ký thành công.
     */
    public record RegisterResponse(@NotBlank String webhookUrl, String accountNumber, String accountName) {}

    @PostMapping("/confirm-webhook")
    public RegisterResponse confirmWebhook(@RequestBody(required = false) RegisterRequest request) {
        RegisterPayosWebhookHandler.Result result =
                registerWebhook.handle(request == null ? null : request.webhookUrl());
        return new RegisterResponse(result.webhookUrl(), result.accountNumber(), result.accountName());
    }
}
