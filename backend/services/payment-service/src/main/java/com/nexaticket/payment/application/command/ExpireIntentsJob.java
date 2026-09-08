// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import com.nexaticket.payment.domain.model.IntentStatus;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đóng các yêu cầu thanh toán quá hạn.
 *
 * <p>Đây là một nửa của race kinh điển ở BRAINSTORM §4.4: webhook đến đúng lúc worker này chạy.
 * Nửa kia là {@code lockByReference} dùng {@code FOR UPDATE} — hai bên tranh cùng một dòng nên
 * PostgreSQL bắt chúng xếp hàng, và bên nào chạy sau cũng đọc được trạng thái mới nhất.
 */
@Service
public class ExpireIntentsJob {

    private static final int BATCH_SIZE = 200;

    private final PaymentIntentRepository intents;
    private final Clock clock;

    public ExpireIntentsJob(PaymentIntentRepository intents, Clock clock) {
        this.intents = intents;
        this.clock = clock;
    }

    @Transactional
    public int runOnce() {
        List<PaymentIntent> expired = intents.claimExpired(clock.instant(), BATCH_SIZE);
        for (PaymentIntent intent : expired) {
            if (intent.close(IntentStatus.EXPIRED)) {
                intents.updateStatus(intent);
            }
        }
        return expired.size();
    }
}
