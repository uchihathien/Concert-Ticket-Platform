// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.realtime.application.AvailabilityUpdate;
import com.nexaticket.realtime.application.UpdateCoalescer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

/** {@link AvailabilityListener} và {@link AvailabilityTopology}. */
class AvailabilityAmqpTest {

    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-a111-111111111111");

    @Nested
    @DisplayName("AvailabilityListener")
    class Listener {

        private final UpdateCoalescer coalescer = new UpdateCoalescer();
        private final AvailabilityListener listener = new AvailabilityListener(coalescer);

        @Test
        @DisplayName("message hợp lệ (version là số hoặc chuỗi số): vào bộ gộp")
        void message_hop_le() {
            listener.onAvailabilityChanged(Map.of("eventSessionId", SESSION.toString(), "version", 12));
            listener.onAvailabilityChanged(Map.of("eventSessionId", SESSION.toString(), "version", "15"));

            assertThat(coalescer.drain()).containsExactly(new AvailabilityUpdate(SESSION, 15));
        }

        @Test
        @DisplayName("message có thêm trường lạ: bỏ qua trường thừa, vẫn nhận")
        void truong_thua() {
            Map<String, Object> message = new HashMap<>();
            message.put("eventSessionId", SESSION.toString());
            message.put("version", 3);
            message.put("zones", List.of("A"));

            listener.onAvailabilityChanged(message);

            assertThat(coalescer.drain()).containsExactly(new AvailabilityUpdate(SESSION, 3));
        }

        @Test
        @DisplayName(
                "message rác (thiếu trường, id sai khuôn, version không phải số): nuốt lỗi, không ném, không gộp gì")
        void message_rac() {
            listener.onAvailabilityChanged(Map.of("version", 1));
            listener.onAvailabilityChanged(Map.of("eventSessionId", SESSION.toString()));
            listener.onAvailabilityChanged(Map.of("eventSessionId", "abc", "version", 1));
            listener.onAvailabilityChanged(Map.of("eventSessionId", SESSION.toString(), "version", "x"));
            listener.onAvailabilityChanged(Map.of());

            assertThat(coalescer.pendingCount()).isZero();
        }
    }

    @Nested
    @DisplayName("AvailabilityTopology")
    class Topology {

        private final AvailabilityTopology topology = new AvailabilityTopology();

        @Test
        @DisplayName("exchange fanout bền, không tự xoá, đúng tên inventory phát vào")
        void exchange() {
            FanoutExchange exchange = topology.availabilityExchange();

            assertThat(exchange.getName()).isEqualTo("nexaticket.availability");
            assertThat(exchange.isDurable()).isTrue();
            assertThat(exchange.isAutoDelete()).isFalse();
        }

        @Test
        @DisplayName("queue riêng mỗi instance: tên ngẫu nhiên, tạm, TTL 30s, trần 10.000, drop-head")
        void queue_rieng_moi_instance() {
            Queue first = topology.instanceQueue();
            Queue second = topology.instanceQueue();

            assertThat(first.getName()).startsWith("rt-gw.").isNotEqualTo(second.getName());
            assertThat(first.isDurable()).isFalse();
            assertThat(first.isExclusive()).isTrue();
            assertThat(first.isAutoDelete()).isTrue();
            assertThat(first.getArguments())
                    .containsEntry("x-message-ttl", 30_000)
                    .containsEntry("x-max-length", 10_000)
                    .containsEntry("x-overflow", "drop-head");
        }

        @Test
        @DisplayName("binding nối queue của instance vào exchange fanout")
        void binding() {
            Queue queue = topology.instanceQueue();
            FanoutExchange exchange = topology.availabilityExchange();

            Binding binding = topology.instanceBinding(queue, exchange);

            assertThat(binding.getDestination()).isEqualTo(queue.getName());
            assertThat(binding.getExchange()).isEqualTo(exchange.getName());
        }

        @Test
        @DisplayName("bộ chuyển đổi là JSON — không Java-deserialize dữ liệu từ broker")
        void converter_la_json() {
            MessageConverter converter = topology.rabbitMessageConverter();

            assertThat(converter).isInstanceOf(Jackson2JsonMessageConverter.class);
        }

        @Test
        @DisplayName("tên bean instanceQueue là hợp đồng với SpEL của listener")
        void ten_bean_la_hop_dong() throws Exception {
            assertThat(AvailabilityTopology.class.getDeclaredMethod("instanceQueue"))
                    .isNotNull();
            var annotation = AvailabilityListener.class
                    .getMethod("onAvailabilityChanged", Map.class)
                    .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
            assertThat(annotation.queues()).containsExactly("#{instanceQueue.name}");
        }
    }
}
