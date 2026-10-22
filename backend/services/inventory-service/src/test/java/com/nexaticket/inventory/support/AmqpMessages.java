// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Dựng message AMQP bằng tay, đúng hình dạng outbox phát ra: {@code messageId} là id dòng outbox,
 * {@code eventType} và {@code aggregateId} nằm ở header.
 */
public final class AmqpMessages {

    private AmqpMessages() {}

    public static Message event(UUID messageId, String eventType, String jsonBody) {
        MessageProperties props = new MessageProperties();
        if (messageId != null) {
            props.setMessageId(messageId.toString());
        }
        props.setHeader("eventType", eventType);
        props.setHeader("aggregateId", UUID.randomUUID().toString());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(jsonBody.getBytes(StandardCharsets.UTF_8), props);
    }
}
