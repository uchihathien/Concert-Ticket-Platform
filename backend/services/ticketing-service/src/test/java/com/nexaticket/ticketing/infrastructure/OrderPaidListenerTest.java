// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import com.nexaticket.ticketing.application.command.IssueTicketsForOrderHandler;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.infrastructure.amqp.OrderPaidListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Consumer {@code order.paid}: message nào được xử lý, message nào bị bỏ, message nào được giao lại.
 *
 * <p>Hàng đợi chỉ có một consumer, nên phân loại sai ở đây không chỉ làm hỏng một đơn — một message
 * bị giao lại mãi sẽ CHẶN ĐẦU HÀNG và mọi đơn phía sau không có vé.
 */
class OrderPaidListenerTest {

    private static final String QUEUE = "ticketing.ordering.paid";

    private IssueTicketsForOrderHandler issueTickets;
    private ProcessedEvents processed;
    private OrderPaidListener listener;

    @BeforeEach
    void setUp() {
        issueTickets = mock(IssueTicketsForOrderHandler.class);
        processed = mock(ProcessedEvents.class);
        listener = new OrderPaidListener(issueTickets, processed, new ObjectMapper());
    }

    @Nested
    @DisplayName("Đường thường")
    class DuongThuong {

        @Test
        @DisplayName("Message mới: đánh dấu đã xử lý theo messageId rồi phát vé cho đúng đơn")
        void message_moi() {
            UUID eventId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            when(processed.markIfNew(QUEUE, eventId.toString())).thenReturn(true);

            listener.onOrderPaid(message(eventId.toString(), "{\"orderId\":\"" + orderId + "\"}"));

            verify(issueTickets).handle(orderId);
        }

        @Test
        @DisplayName("Message trùng (RabbitMQ giao lại): bỏ qua, không gọi Ordering lần nữa")
        void message_trung() {
            UUID eventId = UUID.randomUUID();
            when(processed.markIfNew(QUEUE, eventId.toString())).thenReturn(false);

            listener.onOrderPaid(message(eventId.toString(), "{\"orderId\":\"" + UUID.randomUUID() + "\"}"));

            verifyNoInteractions(issueTickets);
        }
    }

    @Nested
    @DisplayName("Message hỏng")
    class MessageHong {

        @Test
        @DisplayName("Không có messageId: bỏ, không chống trùng được thì không xử lý")
        void khong_co_message_id() {
            listener.onOrderPaid(message(null, "{\"orderId\":\"" + UUID.randomUUID() + "\"}"));

            verifyNoInteractions(processed, issueTickets);
        }

        @Test
        @DisplayName("Thân không phải JSON: bỏ, không ném để khỏi bị giao lại mãi")
        void than_khong_phai_json() {
            listener.onOrderPaid(message(UUID.randomUUID().toString(), "khong-phai-json{"));

            verifyNoInteractions(processed, issueTickets);
        }

        @Test
        @DisplayName("Thiếu orderId: bỏ, không phát vé")
        void thieu_order_id() {
            when(processed.markIfNew(anyString(), anyString())).thenReturn(true);

            listener.onOrderPaid(message(UUID.randomUUID().toString(), "{\"amount\":100}"));

            verify(issueTickets, never()).handle(any());
        }

        @Test
        @DisplayName("orderId không phải UUID: KHÔNG được ném lỗi khiến message bị giao lại vô hạn")
        void order_id_khong_phai_uuid() {
            // Một payload hỏng là hỏng vĩnh viễn — giao lại lần thứ một nghìn vẫn hỏng. Nếu lỗi
            // lọt ra ngoài dưới dạng một ngoại lệ thường, transaction rollback xoá dấu đã xử lý và
            // RabbitMQ giao lại mãi: đúng kiểu chặn đầu hàng mà listener này được viết để tránh.
            when(processed.markIfNew(anyString(), anyString())).thenReturn(true);

            Throwable thrown = catchThrowable(
                    () -> listener.onOrderPaid(message(UUID.randomUUID().toString(), "{\"orderId\":\"DH-0001\"}")));

            assertThat(thrown == null || thrown instanceof AmqpRejectAndDontRequeueException)
                    .as("message hỏng vĩnh viễn phải bị bỏ hoặc đẩy DLQ, nhưng nhận được: %s", thrown)
                    .isTrue();
            verify(issueTickets, never()).handle(any());
        }
    }

    @Nested
    @DisplayName("Ordering trả lời khác nhau")
    class OrderingTraLoi {

        @Test
        @DisplayName("Ordering nói đơn không tồn tại: đẩy sang DLQ, không giao lại")
        void don_khong_ton_tai() {
            UUID orderId = UUID.randomUUID();
            when(processed.markIfNew(anyString(), anyString())).thenReturn(true);
            when(issueTickets.handle(orderId)).thenThrow(new OrderingPort.OrderNotFoundException("404", null));

            assertThatThrownBy(() -> listener.onOrderPaid(
                            message(UUID.randomUUID().toString(), "{\"orderId\":\"" + orderId + "\"}")))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }

        @Test
        @DisplayName("Ordering tạm thời không trả lời: ném nguyên để transaction rollback và message được giao lại")
        void ordering_tam_thoi_hong() {
            UUID orderId = UUID.randomUUID();
            when(processed.markIfNew(anyString(), anyString())).thenReturn(true);
            when(issueTickets.handle(orderId))
                    .thenThrow(new OrderingPort.OrderingUnavailableException("timeout", null));

            assertThatThrownBy(() -> listener.onOrderPaid(
                            message(UUID.randomUUID().toString(), "{\"orderId\":\"" + orderId + "\"}")))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class)
                    .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }

    private static Message message(String messageId, String body) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setHeader("eventType", "order.paid");
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
