// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** AvailabilityChanged: dữ liệu tạm, mất được — không bao giờ làm hỏng lần giữ chỗ đã commit. */
class RabbitAvailabilityPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RabbitAvailabilityPublisher publisher =
            new RabbitAvailabilityPublisher(rabbit, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("Gửi vào fanout exchange với suất, version và thời điểm theo đồng hồ được tiêm")
    void gui_dung_noi_dung() {
        publisher.availabilityChanged(SESSION, 42L);

        verify(rabbit)
                .convertAndSend(
                        "nexaticket.availability",
                        "",
                        Map.of("eventSessionId", SESSION.toString(), "version", 42L, "at", NOW.toString()));
    }

    @Test
    @DisplayName("Broker chết: chỉ log, không ném")
    void broker_chet_khong_nem() {
        doThrow(new AmqpConnectException(new RuntimeException("refused")))
                .when(rabbit)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> publisher.availabilityChanged(SESSION, 1L)).doesNotThrowAnyException();
    }
}
