// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

/**
 * {@link ConsumedEvent}: bóc tách message AMQP cho consumer.
 *
 * <p>Khoá chống trùng là {@code messageId} (id dòng outbox), không phải {@code aggregateId}; và
 * message hỏng vĩnh viễn phải ném {@link ConsumedEvent.MalformedEventException} để consumer ack.
 */
class ConsumedEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID EVENT = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID AGG = UUID.fromString("22222222-2222-4222-a222-222222222222");

    private static MessageBuilder builder(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Message full(String body) {
        return builder(body)
                .setMessageId(EVENT.toString())
                .setHeader("eventType", "order.paid")
                .setHeader("aggregateId", AGG.toString())
                .build();
    }

    @Test
    @DisplayName("message đủ header: eventId lấy từ messageId, không lấy từ aggregateId")
    void boc_dung_header() {
        ConsumedEvent event = ConsumedEvent.from(full("{\"a\":1}"), JSON);

        assertThat(event.eventId()).isEqualTo(EVENT).isNotEqualTo(AGG);
        assertThat(event.eventType()).isEqualTo("order.paid");
        assertThat(event.aggregateId()).isEqualTo(AGG);
        assertThat(event.payload().get("a").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("thiếu messageId: MalformedEventException vì không chống trùng được")
    void thieu_message_id() {
        Message message = builder("{}").setHeader("eventType", "x").build();

        assertThatThrownBy(() -> ConsumedEvent.from(message, JSON))
                .isInstanceOf(ConsumedEvent.MalformedEventException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    @DisplayName("messageId không phải UUID: MalformedEventException, không IllegalArgumentException")
    void message_id_sai_khuon() {
        Message message = builder("{}").setMessageId("khong-phai-uuid").build();

        assertThatThrownBy(() -> ConsumedEvent.from(message, JSON))
                .isInstanceOf(ConsumedEvent.MalformedEventException.class);
    }

    @Test
    @DisplayName("thân không phải JSON: MalformedEventException")
    void than_khong_phai_json() {
        Message message =
                builder("{không phải json").setMessageId(EVENT.toString()).build();

        assertThatThrownBy(() -> ConsumedEvent.from(message, JSON))
                .isInstanceOf(ConsumedEvent.MalformedEventException.class)
                .hasMessageContaining("Không đọc được message");
    }

    @Test
    @DisplayName("aggregateId header sai khuôn: MalformedEventException")
    void aggregate_id_sai_khuon() {
        Message message = builder("{}")
                .setMessageId(EVENT.toString())
                .setHeader("aggregateId", "xyz")
                .build();

        assertThatThrownBy(() -> ConsumedEvent.from(message, JSON))
                .isInstanceOf(ConsumedEvent.MalformedEventException.class);
    }

    @Test
    @DisplayName("thiếu eventType và aggregateId: để null, không ném")
    void thieu_header_tuy_chon() {
        ConsumedEvent event =
                ConsumedEvent.from(builder("{}").setMessageId(EVENT.toString()).build(), JSON);

        assertThat(event.eventType()).isNull();
        assertThat(event.aggregateId()).isNull();
    }

    @Test
    @DisplayName("text(): null khi thiếu hoặc JSON null; giữ tiếng Việt")
    void text_doc_truong() {
        ConsumedEvent event = ConsumedEvent.from(full("{\"ten\":\"Đêm nhạc\",\"x\":null}"), JSON);

        assertThat(event.text("ten")).isEqualTo("Đêm nhạc");
        assertThat(event.text("x")).isNull();
        assertThat(event.text("khong-co")).isNull();
    }

    @Test
    @DisplayName("uuid(): đọc UUID hợp lệ; thiếu hoặc sai khuôn trả null, không ném")
    void uuid_doc_truong() {
        ConsumedEvent event =
                ConsumedEvent.from(full("{\"ok\":\"" + AGG + "\",\"bad\":\"abc\",\"empty\":\"\",\"n\":12}"), JSON);

        assertThat(event.uuid("ok")).isEqualTo(AGG);
        assertThat(event.uuid("bad")).isNull();
        assertThat(event.uuid("empty")).isNull();
        assertThat(event.uuid("n")).isNull();
        assertThat(event.uuid("khong-co")).isNull();
    }

    @Test
    @DisplayName("number(): đọc số, thiếu hoặc null thành 0, chuỗi số được ép")
    void number_doc_truong() {
        ConsumedEvent event = ConsumedEvent.from(full("{\"a\":1500000,\"b\":null,\"c\":\"42\",\"d\":\"abc\"}"), JSON);

        assertThat(event.number("a")).isEqualTo(1_500_000L);
        assertThat(event.number("b")).isZero();
        assertThat(event.number("khong-co")).isZero();
        assertThat(event.number("c")).isEqualTo(42L);
        assertThat(event.number("d")).isZero();
    }
}
