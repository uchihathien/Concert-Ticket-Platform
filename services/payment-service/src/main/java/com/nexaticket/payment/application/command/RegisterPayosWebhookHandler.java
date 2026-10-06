// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.platform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Đăng ký URL webhook với payOS.
 *
 * <p>Phải chạy <b>một lần cho mỗi môi trường</b>, trước khi có bất kỳ đơn thật nào. Không chạy thì payOS
 * không gọi về, và mọi khoản tiền vào sẽ nằm đó tới khi ai đó bấm đối soát tay.
 *
 * <p>payOS <b>gọi thử</b> endpoint webhook ngay trong lời gọi này, với một payload giả
 * ({@code orderCode} 123) và chữ ký thật. Nghĩa là lời gọi này cũng là một phép thử đầu-cuối: nó chỉ thành
 * công khi URL gọi được từ internet, là HTTPS, và checksum key của ta khớp với kênh của họ. Thất bại ở đây
 * là một tin tốt — nó nổ bây giờ chứ không nổ vào khoản tiền đầu tiên.
 */
@Service
public class RegisterPayosWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterPayosWebhookHandler.class);

    private final PayosGateway payos;
    private final PaymentProperties properties;

    public RegisterPayosWebhookHandler(PayosGateway payos, PaymentProperties properties) {
        this.payos = payos;
        this.properties = properties;
    }

    public record Result(String webhookUrl, String accountNumber, String accountName) {}

    /** @param overrideUrl null thì dùng {@code nexaticket.payment.payos.webhook-url} */
    public Result handle(String overrideUrl) {
        String url = overrideUrl == null || overrideUrl.isBlank()
                ? properties.payos().webhookUrl()
                : overrideUrl;
        if (url == null || url.isBlank()) {
            throw new ApiException(
                    PaymentErrorCode.PAYOS_REJECTED,
                    "Chưa có nexaticket.payment.payos.webhook-url và không truyền webhookUrl");
        }

        try {
            PayosGateway.WebhookRegistration registration = payos.confirmWebhook(url);
            log.info(
                    "Đã đăng ký webhook payOS: {} cho kênh của {} ({})",
                    registration.webhookUrl(),
                    registration.accountName(),
                    registration.accountNumber());
            return new Result(registration.webhookUrl(), registration.accountNumber(), registration.accountName());
        } catch (PayosGateway.Unavailable e) {
            log.error("Không gọi được payOS để đăng ký webhook {}", url, e);
            throw new ApiException(PaymentErrorCode.PAYOS_UNAVAILABLE, "payOS unavailable, please retry");
        } catch (PayosGateway.Rejected e) {
            // Gần như luôn là một trong ba: URL không phải HTTPS, payOS không gọi tới được (tường lửa,
            // localhost), hoặc endpoint webhook của ta trả khác 2xx cho payload thử. Câu lỗi của payOS nói
            // rõ cái nào, nên nó được giữ nguyên trong log.
            log.error("payOS từ chối URL webhook {}: [{}] {}", url, e.code(), e.getMessage());
            throw new ApiException(
                    PaymentErrorCode.PAYOS_REJECTED, "payOS rejected the webhook URL: " + e.getMessage());
        }
    }
}
