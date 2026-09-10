// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.infrastructure.amqp;

import com.nexaticket.realtime.application.AvailabilityUpdate;
import com.nexaticket.realtime.application.UpdateCoalescer;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Nhận {@code AvailabilityChanged} từ inventory-service và đẩy vào bộ gộp.
 *
 * <p>Chỉ có đúng một việc đó. Topology (exchange, queue, binding) nằm ở {@link AvailabilityTopology},
 * và việc tách ra không phải chuyện sắp xếp cho gọn: khi ba {@code @Bean} đó còn nằm trong lớp này,
 * service <b>không khởi động được</b> vì một vòng tròn phụ thuộc tự trỏ vào chính nó. Xem javadoc của
 * {@code AvailabilityTopology}.
 *
 * <p>Queue được tham chiếu bằng SpEL theo <b>tên bean</b> {@code instanceQueue}, vì tên queue thật chỉ
 * biết được lúc chạy (mỗi instance một tên ngẫu nhiên).
 */
@Component
public class AvailabilityListener {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityListener.class);

    private final UpdateCoalescer coalescer;

    public AvailabilityListener(UpdateCoalescer coalescer) {
        this.coalescer = coalescer;
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
