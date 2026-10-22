// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.inventory.application.command.SettleReservationHandler;
import com.nexaticket.inventory.support.AmqpMessages;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Consumer {@code order.paid} và {@code order.expired}/{@code order.cancelled}.
 *
 * <p>Hợp đồng chung: message hỏng thì ack và bỏ (không ném — ném là broker giao lại mãi), message
 * trùng thì bỏ, "không có gì để làm" không phải lỗi.
 */
class OrderListenersTest {

    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-a000-0000000000d1");
    private static final String PAID_QUEUE = "inventory.ordering.paid";
    private static final String CLOSED_QUEUE = "inventory.ordering.closed";

    private final SettleReservationHandler settle = mock(SettleReservationHandler.class);
    private final ProcessedEvents processed = mock(ProcessedEvents.class);
    private final ObjectMapper json = new ObjectMapper();

    private final OrderPaidListener paid = new OrderPaidListener(settle, processed, json);
    private final OrderClosedListener closed = new OrderClosedListener(settle, processed, json);

    @BeforeEach
    void setUp() {
        when(processed.markIfNew(anyString(), anyString())).thenReturn(true);
    }

    @Nested
    @DisplayName("order.paid")
    class OrderPaid {

        @Test
        @DisplayName("Message hợp lệ: chốt bán đúng đơn, đánh dấu theo messageId trên đúng queue")
        void chot_ban_dung_don() {
            UUID messageId = UUID.randomUUID();

            paid.onOrderPaid(AmqpMessages.event(messageId, "order.paid", "{\"orderId\":\"" + ORDER + "\"}"));

            verify(processed).markIfNew(PAID_QUEUE, messageId.toString());
            verify(settle).settle(ORDER);
            verify(settle, never()).cancel(any());
        }

        @Test
        @DisplayName("Message trùng (đã xử lý): không chốt bán lần hai")
        void message_trung() {
            UUID messageId = UUID.randomUUID();
            when(processed.markIfNew(PAID_QUEUE, messageId.toString())).thenReturn(false);

            paid.onOrderPaid(AmqpMessages.event(messageId, "order.paid", "{\"orderId\":\"" + ORDER + "\"}"));

            verifyNoInteractions(settle);
        }

        @Test
        @DisplayName("Không có messageId: ack và bỏ — không chống trùng được thì không xử lý")
        void thieu_message_id() {
            assertThatCode(() ->
                            paid.onOrderPaid(AmqpMessages.event(null, "order.paid", "{\"orderId\":\"" + ORDER + "\"}")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(settle, processed);
        }

        @Test
        @DisplayName("Thân message không phải JSON: ack và bỏ, không ném")
        void than_hong() {
            assertThatCode(() -> paid.onOrderPaid(AmqpMessages.event(UUID.randomUUID(), "order.paid", "{not json")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(settle, processed);
        }

        @Test
        @DisplayName("Thiếu orderId: ack và bỏ, không gọi chốt bán với null")
        void thieu_order_id() {
            assertThatCode(() ->
                            paid.onOrderPaid(AmqpMessages.event(UUID.randomUUID(), "order.paid", "{\"amount\":1}")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(settle);
        }

        @Test
        @DisplayName("Không có gì để chốt (handler trả false): không ném — giao lại cũng không đổi được gì")
        void khong_co_gi_de_chot() {
            when(settle.settle(ORDER)).thenReturn(false);

            assertThatCode(() -> paid.onOrderPaid(
                            AmqpMessages.event(UUID.randomUUID(), "order.paid", "{\"orderId\":\"" + ORDER + "\"}")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("orderId không phải UUID: message hỏng vĩnh viễn — ack và bỏ, không ném để broker giao lại mãi")
        void order_id_khong_phai_uuid() {
            assertThatCode(() -> paid.onOrderPaid(
                            AmqpMessages.event(UUID.randomUUID(), "order.paid", "{\"orderId\":\"khong-phai-uuid\"}")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(settle);
        }
    }

    @Nested
    @DisplayName("order.expired / order.cancelled")
    class OrderClosed {

        @Test
        @DisplayName("order.expired: nhả chỗ của đúng đơn, đánh dấu trên queue riêng của consumer này")
        void don_het_han_nha_cho() {
            UUID messageId = UUID.randomUUID();

            closed.onOrderClosed(AmqpMessages.event(messageId, "order.expired", "{\"orderId\":\"" + ORDER + "\"}"));

            verify(processed).markIfNew(CLOSED_QUEUE, messageId.toString());
            verify(settle).cancel(ORDER);
            verify(settle, never()).settle(any());
        }

        @Test
        @DisplayName("order.cancelled đi cùng đường với order.expired")
        void don_huy_cung_duong() {
            closed.onOrderClosed(
                    AmqpMessages.event(UUID.randomUUID(), "order.cancelled", "{\"orderId\":\"" + ORDER + "\"}"));

            verify(settle).cancel(ORDER);
        }

        @Test
        @DisplayName("Message trùng: không nhả chỗ lần hai")
        void message_trung() {
            when(processed.markIfNew(anyString(), anyString())).thenReturn(false);

            closed.onOrderClosed(
                    AmqpMessages.event(UUID.randomUUID(), "order.expired", "{\"orderId\":\"" + ORDER + "\"}"));

            verifyNoInteractions(settle);
        }

        @Test
        @DisplayName("orderId null trong JSON: ack và bỏ")
        void order_id_null() {
            assertThatCode(() -> closed.onOrderClosed(
                            AmqpMessages.event(UUID.randomUUID(), "order.expired", "{\"orderId\":null}")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(settle);
        }

        @Test
        @DisplayName("orderId không phải UUID: ack và bỏ, không ném để broker giao lại mãi")
        void order_id_khong_phai_uuid() {
            assertThatCode(() -> closed.onOrderClosed(
                            AmqpMessages.event(UUID.randomUUID(), "order.expired", "{\"orderId\":\"12345\"}")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(settle);
        }

        @Test
        @DisplayName("messageId không phải UUID: ack và bỏ")
        void message_id_hong() {
            var message = AmqpMessages.event(UUID.randomUUID(), "order.expired", "{\"orderId\":\"" + ORDER + "\"}");
            message.getMessageProperties().setMessageId("khong-phai-uuid");

            assertThatCode(() -> closed.onOrderClosed(message)).doesNotThrowAnyException();

            verifyNoInteractions(settle, processed);
        }

        @Test
        @DisplayName("Đường HTTP đã nhả trước (cancel trả false): không ném")
        void duong_nhanh_da_nha_truoc() {
            when(settle.cancel(ORDER)).thenReturn(false);

            assertThatCode(() -> closed.onOrderClosed(
                            AmqpMessages.event(UUID.randomUUID(), "order.expired", "{\"orderId\":\"" + ORDER + "\"}")))
                    .doesNotThrowAnyException();
        }
    }
}
