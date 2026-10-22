// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.analytics.application.command.ApplySalesDeltaHandler;
import com.nexaticket.analytics.domain.model.SalesDelta;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

/**
 * Consumer của analytics: mỗi loại sự kiện thành một delta khác nhau; message không dùng được thì
 * ack và bỏ qua thay vì ném (giao lại mãi sẽ chặn hàng đợi).
 */
class OrderEventsListenerTest {

    private static final UUID MESSAGE_ID = UUID.fromString("99999999-9999-4999-a999-999999999999");
    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID ORG = UUID.fromString("33333333-3333-4333-a333-333333333333");

    private final ApplySalesDeltaHandler handler = mock(ApplySalesDeltaHandler.class);
    private final OrderEventsListener listener = new OrderEventsListener(handler, new ObjectMapper());

    private static String ids() {
        return "\"eventSessionId\":\"" + SESSION + "\",\"eventId\":\"" + EVENT + "\",\"organizationId\":\"" + ORG
                + "\"";
    }

    private static Message message(String type, String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setMessageId(MESSAGE_ID.toString())
                .setHeader("eventType", type)
                .build();
    }

    private SalesDelta applied(String type) {
        ArgumentCaptor<SalesDelta> delta = ArgumentCaptor.forClass(SalesDelta.class);
        verify(handler)
                .handle(
                        org.mockito.ArgumentMatchers.eq(MESSAGE_ID),
                        org.mockito.ArgumentMatchers.eq(type),
                        delta.capture());
        return delta.getValue();
    }

    @Test
    @DisplayName("order.paid: cộng số vé và tổng tiền trong payload")
    void order_paid() {
        listener.onOrderEvent(message("order.paid", "{" + ids() + ",\"ticketCount\":3,\"totalVnd\":1500000}"));

        assertThat(applied("order.paid")).isEqualTo(SalesDelta.orderPaid(SESSION, EVENT, ORG, 3, 1_500_000));
    }

    @Test
    @DisplayName("order.refunded: trừ ngược vé và tiền")
    void order_refunded() {
        listener.onOrderEvent(message("order.refunded", "{" + ids() + ",\"ticketCount\":2,\"totalVnd\":900000}"));

        assertThat(applied("order.refunded")).isEqualTo(SalesDelta.refunded(SESSION, EVENT, ORG, 2, 900_000));
    }

    @Test
    @DisplayName("order.expired và order.cancelled: chỉ đếm đơn")
    void expired_cancelled() {
        listener.onOrderEvent(message("order.expired", "{" + ids() + "}"));
        listener.onOrderEvent(message("order.cancelled", "{" + ids() + "}"));

        assertThat(applied("order.expired")).isEqualTo(SalesDelta.orderExpired(SESSION, EVENT, ORG));
        assertThat(applied("order.cancelled")).isEqualTo(SalesDelta.orderCancelled(SESSION, EVENT, ORG));
    }

    @Test
    @DisplayName("order.paid thiếu ticketCount/totalVnd: delta 0 thay vì ném")
    void paid_thieu_so() {
        listener.onOrderEvent(message("order.paid", "{" + ids() + "}"));

        assertThat(applied("order.paid")).isEqualTo(SalesDelta.orderPaid(SESSION, EVENT, ORG, 0, 0));
    }

    @Test
    @DisplayName("order.created và loại không biết: bỏ qua, không đụng read model")
    void loai_khong_dong_gop() {
        listener.onOrderEvent(message("order.created", "{" + ids() + "}"));
        listener.onOrderEvent(message("order.weird", "{" + ids() + "}"));
        listener.onOrderEvent(MessageBuilder.withBody(("{" + ids() + "}").getBytes(StandardCharsets.UTF_8))
                .setMessageId(MESSAGE_ID.toString())
                .build());

        verifyNoInteractions(handler);
    }

    @Test
    @DisplayName("thiếu một trong ba id (đơn cũ không có eventId) hoặc id sai khuôn: bỏ qua, không bịa dòng")
    void thieu_id() {
        listener.onOrderEvent(
                message("order.paid", "{\"eventSessionId\":\"" + SESSION + "\",\"organizationId\":\"" + ORG + "\"}"));
        listener.onOrderEvent(message(
                "order.paid",
                "{\"eventSessionId\":\"abc\",\"eventId\":\"" + EVENT + "\",\"organizationId\":\"" + ORG + "\"}"));

        verifyNoInteractions(handler);
    }

    @Test
    @DisplayName("message hỏng (không messageId, không phải JSON): bỏ qua, không ném")
    void message_hong() {
        listener.onOrderEvent(
                MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).build());
        listener.onOrderEvent(message("order.paid", "không phải json"));

        verify(handler, org.mockito.Mockito.never()).handle(any(), any(), any());
    }
}
