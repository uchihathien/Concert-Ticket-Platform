// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.infrastructure.amqp;

import com.nexaticket.realtime.application.AvailabilityUpdate;
import com.nexaticket.realtime.application.UpdateCoalescer;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Nhận {@code AvailabilityChanged} từ inventory-service.
 *
 * <h2>Vì sao queue tạm, riêng cho mỗi instance</h2>
 *
 * <p>Client nào nối vào instance nào là ngẫu nhiên (load balancer quyết định), nên <b>mọi</b>
 * instance đều phải nhận <b>mọi</b> message. Một queue dùng chung sẽ chia message giữa các
 * instance và client nối vào instance không nhận được sẽ không bao giờ thấy cập nhật.
 *
 * <p>Queue tự xoá khi instance chết, nên không tích tụ queue rác sau mỗi lần deploy.
 *
 * <h2>Vì sao cố ý để mất message</h2>
 *
 * <p>TTL 30 giây và giới hạn độ dài với {@code drop-head}: dữ liệu khả dụng ghế cũ vô giá trị,
 * thà bỏ còn hơn dồn ứ. Client phát hiện version nhảy sẽ tự fetch lại — mất một message không
 * gây hậu quả gì, còn một hàng đợi dài vô hạn thì làm cả instance chết vì hết bộ nhớ.
 */
@Component
public class AvailabilityListener {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityListener.class);

    static final String EXCHANGE = "nexaticket.availability";

    private final UpdateCoalescer coalescer;

    public AvailabilityListener(UpdateCoalescer coalescer) {
        this.coalescer = coalescer;
    }

    @Bean
    FanoutExchange availabilityExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    /**
     * Queue riêng cho instance này: exclusive, auto-delete, có TTL và trần độ dài.
     */
    @Bean
    Queue instanceQueue() {
        return new AnonymousQueue(new org.springframework.amqp.core.NamingStrategy() {
            @Override
            public String generateName() {
                return "rt-gw." + UUID.randomUUID();
            }
        }) {
            @Override
            public Map<String, Object> getArguments() {
                return Map.of(
                        "x-message-ttl", 30_000,
                        "x-max-length", 10_000,
                        "x-overflow", "drop-head");
            }
        };
    }

    @Bean
    Binding instanceBinding(Queue instanceQueue, FanoutExchange availabilityExchange) {
        return BindingBuilder.bind(instanceQueue).to(availabilityExchange);
    }

    @RabbitListener(queues = "#{instanceQueue.name}")
    public void onAvailabilityChanged(Map<String, Object> message) {
        try {
            coalescer.accept(new AvailabilityUpdate(
                    UUID.fromString(message.get("eventSessionId").toString()),
                    Long.parseLong(message.get("version").toString())));
        } catch (RuntimeException e) {
            // Message rác không được làm chết listener: nó sẽ bị giao lại mãi và chặn cả queue.
            log.warn("Bỏ qua message khả dụng không đọc được: {}", message, e);
        }
    }
}
