// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.job;

import com.nexaticket.payment.application.command.ExpireIntentsJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker của Payment.
 *
 * <p>Tách khỏi lớp nghiệp vụ để test gọi thẳng {@code runOnce()} và khẳng định kết quả, thay vì
 * phải chờ đồng hồ. Tắt được bằng cấu hình vì worker chạy ngầm sẽ làm integration test bập bênh —
 * ở đây đặc biệt đúng: một job đóng intent chạy giữa chừng sẽ làm mọi test webhook đỏ ngẫu nhiên.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(value = "nexaticket.payment.workers.enabled", matchIfMissing = true)
public class PaymentSchedulers {

    private final ExpireIntentsJob expireIntents;

    public PaymentSchedulers(ExpireIntentsJob expireIntents) {
        this.expireIntents = expireIntents;
    }

    /**
     * Thưa hơn nhịp 15 giây của ordering-service là cố ý: đây chỉ là lưới dọn dẹp, còn đường chính
     * đóng intent là lời gọi từ Ordering ngay lúc đơn đóng.
     */
    @Scheduled(fixedDelayString = "${nexaticket.payment.workers.expire-interval:60s}")
    public void expireIntents() {
        expireIntents.runOnce();
    }
}
