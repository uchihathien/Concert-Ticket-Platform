// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.infrastructure.amqp;

import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.NamingStrategy;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology RabbitMQ của realtime gateway: fanout exchange + một queue tạm riêng cho instance này.
 *
 * <h2>Vì sao queue tạm, riêng cho mỗi instance</h2>
 *
 * <p>Client nào nối vào instance nào là ngẫu nhiên (load balancer quyết định), nên <b>mọi</b> instance
 * đều phải nhận <b>mọi</b> message. Một queue dùng chung sẽ chia message giữa các instance, và client
 * nối vào instance không nhận được sẽ không bao giờ thấy cập nhật.
 *
 * <p>Queue tự xoá khi instance chết, nên không tích tụ queue rác sau mỗi lần deploy.
 *
 * <h2>Vì sao cố ý để mất message</h2>
 *
 * <p>TTL 30 giây và giới hạn độ dài với {@code drop-head}: dữ liệu khả dụng ghế cũ vô giá trị, thà bỏ
 * còn hơn dồn ứ. Client phát hiện version nhảy sẽ tự fetch lại — mất một message không gây hậu quả gì,
 * còn một hàng đợi dài vô hạn thì làm cả instance chết vì hết bộ nhớ.
 *
 * <h2>Vì sao TÁCH khỏi {@link AvailabilityListener}</h2>
 *
 * <p>Ba {@code @Bean} này từng nằm trong {@code AvailabilityListener} — một {@code @Component} vừa khai
 * bean, vừa là bean tiêu thụ chính những bean đó. Kết quả: service <b>không khởi động được</b> với một
 * vòng tròn phụ thuộc <i>tự trỏ vào chính nó</i>:
 *
 * <pre>
 *   instanceBinding(instanceQueue, availabilityExchange)
 *        └─ cả ba method đều nằm trên bean availabilityListener
 *           ⇒ availabilityListener cần availabilityListener
 * </pre>
 *
 * <p>Cùng một lỗi với {@code WebSocketConfig} trước đây, và cùng một bài học: <b>một lớp không nên vừa
 * cung cấp bean vừa là bean phụ thuộc vào chúng</b>. {@code @Bean} trên một {@code @Component} (không
 * phải {@code @Configuration}) chạy ở "lite mode" và vẫn cần thực thể của lớp chứa nó, nên nó không an
 * toàn như trông thấy.
 *
 * <p>Cách chữa đúng không phải {@code spring.main.allow-circular-references=true}: cờ đó chỉ ẩn vòng
 * tròn đi, và thứ tự khởi tạo sẽ phụ thuộc vào may mắn.
 */
@Configuration
public class AvailabilityTopology {

    /** Tên exchange inventory-service phát {@code AvailabilityChanged} vào. */
    static final String EXCHANGE = "nexaticket.availability";

    @Bean
    FanoutExchange availabilityExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    /**
     * Queue riêng cho instance này: exclusive, auto-delete, có TTL và trần độ dài.
     *
     * <p>Tên bean là {@code instanceQueue} và {@link AvailabilityListener} tham chiếu nó bằng SpEL
     * ({@code "#{instanceQueue.name}"}) — <b>đổi tên method là đổi hợp đồng</b>, và lỗi sẽ chỉ lộ ra ở
     * lúc chạy dưới dạng "không resolve được queue".
     */
    @Bean
    Queue instanceQueue() {
        return new AnonymousQueue(new NamingStrategy() {
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

    /**
     * JSON cho message nhận về — <b>phải khớp bên phát</b>.
     *
     * <p>Thiếu bean này thì container dùng {@code SimpleMessageConverter}, và nó cố Java-deserialize
     * payload rồi bị chính allow-list của Spring AMQP chặn:
     *
     * <pre>
     *   SecurityException: Attempt to deserialize unauthorized class java.util.CollSer
     * </pre>
     *
     * <p>Cách chữa <b>không phải</b> {@code SPRING_AMQP_DESERIALIZATION_TRUST_ALL} như câu gợi ý trong
     * chính thông báo lỗi: mở allow-list là nhận Java-deserialize dữ liệu từ broker, tức là mở một
     * đường thực thi mã từ xa nếu có ngày ai đó đẩy được message vào exchange này. JSON không có lớp
     * để dựng nên không có đường đó.
     */
    @Bean
    MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
