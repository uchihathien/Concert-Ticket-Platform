// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Đóng các yêu cầu thanh toán đã quá hạn.
 *
 * <p>Lưới an toàn, không phải đường chính. Đường chính là ordering-service gọi
 * {@code DELETE /internal/payment-intents/{orderId}} khi đơn đóng — nó vừa đổi trạng thái vừa đóng
 * link payOS. Job này dọn những gì lọt qua: một lời gọi hỏng vì mạng, hoặc một intent mồ côi do
 * tiến trình chết đúng giữa "tạo link xong" và "ghi đơn xong".
 *
 * <p><b>Chỉ đóng trong database, không gọi payOS.</b> Link đã được tạo với {@code expiredAt} bằng
 * đúng hạn thanh toán của đơn, nên tới thời điểm job này chạm tới thì chính payOS đã đóng nó. Gọi
 * thêm một vòng huỷ cho mỗi dòng chỉ để nhận về "link đã hết hạn" là đổi một khoản thời gian mạng
 * lấy không gì cả.
 *
 * <p>Việc đóng là một câu {@code UPDATE … WHERE status = 'PENDING'}. Không đọc rồi ghi: giữa hai
 * bước đó một webhook có thể vừa xác nhận tiền thật, và ghi đè nó bằng EXPIRED là xoá dấu vết của
 * khoản tiền đó.
 */
@Service
public class ExpireIntentsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireIntentsJob.class);
    private static final int BATCH_SIZE = 200;

    private final PaymentIntentRepository intents;
    private final Clock clock;

    public ExpireIntentsJob(PaymentIntentRepository intents, Clock clock) {
        this.intents = intents;
        this.clock = clock;
    }

    /** @return số intent vừa đóng — trả về để test khẳng định được, thay vì phải chờ và đoán */
    public int runOnce() {
        int closed = intents.expirePending(clock.instant(), BATCH_SIZE);
        if (closed > 0) {
            log.info("Đã đóng {} yêu cầu thanh toán quá hạn", closed);
        }
        return closed;
    }
}
