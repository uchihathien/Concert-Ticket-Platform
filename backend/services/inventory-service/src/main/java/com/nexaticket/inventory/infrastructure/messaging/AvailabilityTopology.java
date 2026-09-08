// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.messaging;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khai báo exchange khả dụng.
 *
 * <p>Fanout chứ không topic: mọi instance của realtime-gateway đều phải nhận mọi message, vì client
 * nào nối vào instance nào là ngẫu nhiên (services.md §4).
 */
@Configuration
public class AvailabilityTopology {

    @Bean
    public FanoutExchange availabilityExchange() {
        return new FanoutExchange(RabbitAvailabilityPublisher.EXCHANGE, true, false);
    }
}
