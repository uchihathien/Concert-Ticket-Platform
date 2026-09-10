// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.messaging;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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

    /**
     * JSON cho mọi message gửi bằng {@code convertAndSend}.
     *
     * <p><b>Không có bean này thì luồng cập nhật sơ đồ chỗ hỏng hoàn toàn, im lặng ở phía phát.</b>
     * Mặc định {@code RabbitTemplate} dùng {@code SimpleMessageConverter}, và nó <b>Java-serialize</b>
     * một {@code Map}. Bên nhận từ chối deserialize lớp không nằm trong allow-list:
     *
     * <pre>
     *   SecurityException: Attempt to deserialize unauthorized class java.util.CollSer
     * </pre>
     *
     * <p>Message hỏng lại bị giao lại liên tục trong suốt TTL 30 giây của nó, nên một message sinh ra
     * hàng nghìn dòng lỗi. Đo được ở môi trường dev: <b>281.631 lần</b> — và phía inventory không có
     * dấu hiệu gì, vì với nó lần publish nào cũng thành công.
     *
     * <p>JSON là định dạng của mọi message khác trong hệ thống (outbox dựng body JSON bằng tay), nên
     * đây cũng là chỗ đưa đường này về cùng một quy ước — không phải một lần vá riêng.
     *
     * <p>Không ảnh hưởng outbox: {@code OutboxPublisher} gọi {@code send()} với một {@code Message}
     * đã dựng sẵn, và {@code send()} không đi qua converter. Cũng không ảnh hưởng ba listener của
     * service này: chúng nhận {@code Message} thô và tự đọc payload.
     */
    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
