// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentReference;
import com.nexaticket.payment.domain.model.PayosLink;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mở yêu cầu thanh toán cho một đơn — bước 7 của saga checkout.
 *
 * <p><b>Idempotent theo orderId</b>, và đây không phải chuyện lịch sự với client: Ordering gọi bước này
 * với hạn 2 giây, và một lần timeout khiến nó không biết intent đã được mở hay chưa. Lần gọi thứ hai
 * phải trả về <b>đúng link payOS lần đầu</b> — tạo link mới nghĩa là khách đang nhìn một mã QR mà hệ
 * thống không còn công nhận, và tiền chuyển vào tài khoản ảo đó sẽ không khớp đơn nào.
 *
 * <p><b>Vì sao không {@code @Transactional}.</b> Giữa hai lần chạm database có một lời gọi HTTP sang
 * payOS, mất tới vài giây. Giữ một transaction mở suốt thời gian đó là giữ một kết nối trong pool 10
 * cái cho một việc không cần database — đủ để một đợt checkout đông làm cạn pool. Phần ghi thực sự chỉ
 * là một câu INSERT, nó tự nguyên tử.
 *
 * <p><b>Thứ tự: gọi payOS trước, ghi sau.</b> Ngược lại thì khi payOS hỏng ta để lại một intent không
 * có gì để quét, và khách nhìn một màn hình thanh toán trống mà không ai biết tại sao. Giá phải trả cho
 * thứ tự này là những link mồ côi khi tiến trình chết đúng giữa hai bước — và chúng tự hết hạn theo
 * {@code expiredAt} đã đặt bằng hạn thanh toán của đơn.
 */
@Service
public class OpenIntentHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenIntentHandler.class);

    private final PaymentIntentRepository intents;
    private final PayosGateway payos;
    private final PaymentProperties properties;

    public OpenIntentHandler(PaymentIntentRepository intents, PayosGateway payos, PaymentProperties properties) {
        this.intents = intents;
        this.payos = payos;
        this.properties = properties;
    }

    public record Command(UUID orderId, UUID organizationId, long amountVnd, Instant expiresAt) {}

    /**
     * Hình dạng trả về cho ordering-service.
     *
     * @param vietQrPayload chuỗi EMVCo payOS sinh; frontend tự render thành QR
     * @param checkoutUrl trang thanh toán payOS host — đường chính cho khách, QR là đường phụ
     */
    public record Result(
            String paymentReference,
            String vietQrPayload,
            String checkoutUrl,
            String bankBin,
            String bankAccountNumber,
            String bankAccountName) {}

    public Result handle(Command cmd) {
        // Đã có intent thì KHÔNG gọi payOS. Ngoài chuyện trả về đúng mã khách đang nhìn, đây còn là
        // thứ giữ cho một saga chạy lại nhiều lần không tạo ra nhiều link thanh toán cho cùng một đơn.
        Optional<PaymentIntent> existing = intents.findByOrder(cmd.orderId());
        if (existing.isPresent()) {
            return resultOf(existing.get());
        }

        long orderCode = intents.nextOrderCode();
        PaymentReference reference = PaymentReference.forOrderCode(orderCode);
        PayosLink link = createLink(cmd, orderCode, reference);

        PaymentIntent stored = intents.insertIfAbsent(PaymentIntent.open(
                cmd.orderId(), cmd.organizationId(), cmd.amountVnd(), cmd.expiresAt(), reference, link));

        if (stored.payosOrderCode() != orderCode) {
            // Thua cuộc đua: một request song song đã mở intent cho đơn này trước. Link ta vừa tạo
            // không ai biết tới, nên phải tự đóng — một link sống mà không nằm trong intent nào là một
            // khoản tiền sẽ vào rồi mồ côi, và đối soát tay là cách duy nhất tìm lại nó.
            closeOrphanLink(orderCode, stored.payosOrderCode());
        }
        return resultOf(stored);
    }

    private PayosLink createLink(Command cmd, long orderCode, PaymentReference reference) {
        PaymentProperties.Payos payosConfig = properties.payos();
        try {
            return payos.createPaymentLink(new PayosGateway.NewLink(
                    orderCode,
                    cmd.amountVnd(),
                    reference.value(),
                    cmd.expiresAt(),
                    payosConfig.returnUrl(cmd.orderId()),
                    payosConfig.cancelUrl(cmd.orderId())));
        } catch (PayosGateway.Unavailable e) {
            // 503: saga checkout bù trừ rồi mời khách thử lại, và lần sau có thể thành công.
            log.warn("payOS không phản hồi khi mở link cho đơn {}", cmd.orderId(), e);
            throw new ApiException(PaymentErrorCode.PAYOS_UNAVAILABLE, "payOS unavailable, please retry");
        } catch (PayosGateway.Rejected e) {
            // 502: thử lại sẽ nhận đúng câu trả lời đó. Cần một con người đọc log.
            log.error("payOS từ chối mở link cho đơn {}: [{}] {}", cmd.orderId(), e.code(), e.getMessage());
            throw new ApiException(PaymentErrorCode.PAYOS_REJECTED, "payOS rejected the payment link");
        }
    }

    private void closeOrphanLink(long orphanOrderCode, long winningOrderCode) {
        log.info("Đơn đã có link payOS {} trước; đóng link mồ côi {}", winningOrderCode, orphanOrderCode);
        try {
            payos.cancelPaymentLink(orphanOrderCode, "Đơn đã có link thanh toán khác");
        } catch (RuntimeException e) {
            // Không ném tiếp: khách đã có một intent hợp lệ và checkout phải thành công. Link mồ côi
            // sẽ tự hết hạn theo expiredAt. Nhưng log ở mức WARN vì trong khoảng đó nó vẫn nhận được
            // tiền, và nếu chuyện này xảy ra thường xuyên thì có một lỗi khác ở tầng trên.
            log.warn("Không đóng được link payOS mồ côi {} — nó sẽ tự hết hạn", orphanOrderCode, e);
        }
    }

    private static Result resultOf(PaymentIntent intent) {
        return new Result(
                intent.reference().value(),
                intent.vietQrPayload(),
                intent.checkoutUrl(),
                intent.bankBin(),
                intent.bankAccountNumber(),
                intent.bankAccountName());
    }
}
