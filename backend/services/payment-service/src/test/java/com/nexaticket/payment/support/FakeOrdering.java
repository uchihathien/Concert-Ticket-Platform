// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.support;

import com.nexaticket.payment.domain.port.OrderingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** ordering-service giả, ghi lại lời gọi và bật/tắt được lỗi. */
@TestConfiguration
public class FakeOrdering {

    @Bean
    @Primary
    public Fake fakeOrdering() {
        return new Fake();
    }

    public static class Fake implements OrderingPort {

        public final List<UUID> confirmed = new ArrayList<>();
        public boolean unavailable;

        @Override
        public void confirmPayment(UUID orderId) {
            if (unavailable) {
                throw new OrderingUnavailableException("giả lập ordering hỏng", null);
            }
            confirmed.add(orderId);
        }
    }
}
