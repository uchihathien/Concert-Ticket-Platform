// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bù trừ của saga: đóng yêu cầu thanh toán khi checkout hỏng.
 *
 * <p>Ba tính chất bắt buộc, vì đây là một bước bù trừ và bù trừ thì chạy lại nhiều lần:
 *
 * <ul>
 *   <li><b>Không có intent cũng là thành công.</b> Saga ghi cờ {@code paymentIntentOpen} TRƯỚC khi gọi,
 *       nên nó có thể bù trừ một việc chưa từng xảy ra. Ném lỗi ở đây sẽ khiến saga kẹt ở
 *       {@code COMPENSATION_PENDING} mãi mãi vì một việc vốn không cần làm.
 *   <li><b>Đã nhận tiền thì không huỷ.</b> Trả về false và để nguyên. Huỷ một yêu cầu đã có tiền vào là
 *       xoá dấu vết của một khoản tiền có thật.
 *   <li><b>Phải đóng cả link ở phía payOS.</b> Mới từ ADR-0016, và nó không phải chuyện dọn dẹp cho
 *       sạch: mỗi link payOS có một tài khoản ảo sống riêng. Huỷ intent ở database mà để link sống nghĩa
 *       là khách mở lại tab cũ, chuyển tiền, tiền vào thật — và vào một đơn không còn ghế. Đó là trường
 *       hợp tệ nhất của cả luồng vì nó phải hoàn bằng tay.
 * </ul>
 *
 * <p><b>Vì sao không {@code @Transactional}.</b> Việc ghi là đúng một câu UPDATE có điều kiện
 * {@code status = 'PENDING'}, nó tự nguyên tử và tự chống ghi đè — một transaction bao quanh không thêm
 * gì. Đổi lại, lời gọi sang payOS không còn nằm trong transaction, nên nó không giữ kết nối database
 * suốt mấy giây chờ mạng.
 */
@Service
public class CancelIntentHandler {

    private static final Logger log = LoggerFactory.getLogger(CancelIntentHandler.class);

    private final PaymentIntentRepository intents;
    private final PayosGateway payos;
    private final java.time.Clock clock;

    public CancelIntentHandler(PaymentIntentRepository intents, PayosGateway payos, java.time.Clock clock) {
        this.intents = intents;
        this.payos = payos;
        this.clock = clock;
    }

    /** @return true nếu lần gọi này thực sự huỷ; false nếu không có gì để huỷ hoặc đã nhận tiền */
    public boolean handle(UUID orderId) {
        Optional<PaymentIntent> found = intents.findByOrder(orderId);
        if (found.isEmpty()) {
            return false;
        }
        PaymentIntent intent = found.get();
        if (intent.isConfirmed()) {
            log.warn("Bỏ qua yêu cầu huỷ intent của đơn {}: đã nhận tiền lúc {}", orderId, intent.confirmedAt());
            return false;
        }
        if (!intent.cancel(clock.instant()) || !intents.updateStatusIfPending(intent)) {
            // Đã đóng từ trước, hoặc một tiến trình khác vừa đóng xong. Cả hai đều là "không còn gì để
            // làm", không phải lỗi — nhưng cũng không gọi payOS nữa: lượt huỷ link thuộc về lượt ghi đã
            // thắng, và gọi lần thứ hai chỉ nhận về một lời từ chối gây nhiễu log.
            return false;
        }

        closePayosLink(intent);
        return true;
    }

    /**
     * Đóng link payOS — best-effort, và cố ý không ném.
     *
     * <p>Intent đã CANCELLED trong database rồi; ném ở đây sẽ khiến saga coi cả bước bù trừ là thất bại
     * và chạy lại một việc đã xong. Link không đóng được vẫn tự hết hạn theo {@code expiredAt} ta đặt
     * bằng hạn thanh toán của đơn, nên cửa sổ rủi ro là hữu hạn — nhưng nó có thật, nên log ở WARN.
     */
    private void closePayosLink(PaymentIntent intent) {
        try {
            payos.cancelPaymentLink(intent.payosOrderCode(), "Đơn hàng đã đóng trước khi thanh toán");
        } catch (PayosGateway.Rejected e) {
            // Thường là "link đã thanh toán" hoặc "link đã huỷ". Nếu payOS nói đã thanh toán mà database
            // của ta nói PENDING thì có một webhook bị mất — đúng lúc cần đến lệnh đối soát.
            log.warn(
                    "payOS không cho đóng link {} của đơn {}: [{}] {}",
                    intent.payosOrderCode(),
                    intent.orderId(),
                    e.code(),
                    e.getMessage());
        } catch (RuntimeException e) {
            log.warn(
                    "Không đóng được link payOS {} của đơn {} — nó sẽ tự hết hạn lúc {}",
                    intent.payosOrderCode(),
                    intent.orderId(),
                    intent.expiresAt(),
                    e);
        }
    }
}
