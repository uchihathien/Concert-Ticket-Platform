// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.messaging;

import com.nexaticket.inventory.domain.port.AvailabilityPublisher;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Đẩy {@code AvailabilityChanged} vào fanout exchange cho realtime-gateway.
 *
 * <p><b>Không đi qua outbox.</b> Đây là dữ liệu tạm, tần suất cao, mất được: client phát hiện nhảy
 * version sẽ tự refetch. Ghi mọi thay đổi ghế vào outbox sẽ làm bảng outbox — vốn được giữ vĩnh
 * viễn làm nhật ký sự kiện (ADR-1009) — phình lên vì thứ không ai đọc lại bao giờ.
 */
@Component
public class RabbitAvailabilityPublisher implements AvailabilityPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitAvailabilityPublisher.class);

    static final String EXCHANGE = "nexaticket.availability";

    private final RabbitTemplate rabbit;
    private final Clock clock;

    public RabbitAvailabilityPublisher(RabbitTemplate rabbit, Clock clock) {
        this.rabbit = rabbit;
        this.clock = clock;
    }

    @Override
    public void availabilityChanged(UUID eventSessionId, long version) {
        try {
            rabbit.convertAndSend(
                    EXCHANGE,
                    "",
                    Map.of(
                            "eventSessionId", eventSessionId.toString(),
                            "version", version,
                            "at", clock.instant().toString()));
        } catch (AmqpException e) {
            // Chỉ log. Ném lên sẽ làm hỏng một lần giữ chỗ đã commit thành công chỉ vì
            // sơ đồ ghế của người khác chậm cập nhật vài giây.
            log.warn("Không publish được AvailabilityChanged cho suất {}", eventSessionId, e);
        }
    }
}
