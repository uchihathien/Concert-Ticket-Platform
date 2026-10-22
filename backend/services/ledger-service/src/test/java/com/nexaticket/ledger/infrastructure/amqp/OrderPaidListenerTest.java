// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.application.command.RecordPaymentHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Consumer {@code order.paid}: dịch message thành lệnh ghi sổ, và với message hỏng thì <b>ack rồi log</b>
 * thay vì ném — một message ném mãi sẽ được giao lại mãi và chặn cả hàng đợi tuần tự
 * ({@code concurrency = 1}) phía sau nó.
 *
 * <p>Ghi chú về cách khẳng định "không ném": service không khai error handler hay DLQ nào cho listener
 * này, nên với Spring AMQP mặc định ({@code defaultRequeueRejected = true}) một exception thoát khỏi
 * {@code onOrderPaid} đồng nghĩa với giao lại vô hạn.
 */
@ExtendWith(MockitoExtension.class)
class OrderPaidListenerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORDER = UUID.fromString("7a1c2f0e-1111-4222-8333-944455556666");
    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");

    @Mock
    RecordPaymentHandler recordPayment;

    OrderPaidListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderPaidListener(recordPayment, JSON);
    }

    private static Message message(String messageId, String body) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setHeader("eventType", "order.paid");
        props.setHeader("aggregateId", ORDER.toString());
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static Message message(String body) {
        return message(UUID.randomUUID().toString(), body);
    }

    private static String payload(String orderId, String orgId, String paidAt, String total, String commission) {
        return """
                {"orderId":%s,"organizationId":%s,"paidAt":%s,"totalVnd":%s,"commissionVnd":%s,"commissionBps":500}
                """
                .formatted(quote(orderId), quote(orgId), quote(paidAt), total, commission);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static final String VALID =
            payload(ORDER.toString(), ORG.toString(), "2026-09-09T03:00:00Z", "3000000", "150000");

    @Nested
    @DisplayName("Message hợp lệ")
    class HopLe {

        @Test
        @DisplayName("Dịch đúng: khoá theo đơn, hoa hồng NGUYÊN từ message, thời điểm là paidAt")
        void dich_dung() {
            when(recordPayment.handle(any())).thenReturn(UUID.randomUUID());

            listener.onOrderPaid(message(VALID));

            ArgumentCaptor<RecordPaymentHandler.Command> captor =
                    ArgumentCaptor.forClass(RecordPaymentHandler.Command.class);
            verify(recordPayment).handle(captor.capture());
            assertThat(captor.getValue())
                    .isEqualTo(RecordPaymentHandler.Command.ofOrder(
                            ORDER,
                            ORG,
                            Money.ofVnd(3_000_000),
                            Money.ofVnd(150_000),
                            500,
                            Instant.parse("2026-09-09T03:00:00Z")));
            assertThat(captor.getValue().idempotencyKey()).isEqualTo("order:" + ORDER);
        }

        @Test
        @DisplayName("Cùng một đơn tới bằng hai message khác messageId: cùng một khoá chống trùng")
        void hai_message_cung_don() {
            when(recordPayment.handle(any())).thenReturn(UUID.randomUUID());

            listener.onOrderPaid(message(VALID));
            listener.onOrderPaid(message(VALID));

            ArgumentCaptor<RecordPaymentHandler.Command> captor =
                    ArgumentCaptor.forClass(RecordPaymentHandler.Command.class);
            verify(recordPayment, times(2)).handle(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(RecordPaymentHandler.Command::idempotencyKey)
                    .containsOnly("order:" + ORDER);
        }
    }

    @Nested
    @DisplayName("Message hỏng: ack và bỏ qua, không ghi sổ")
    class Hong {

        @Test
        @DisplayName("Không có messageId")
        void khong_message_id() {
            Message message = new Message(VALID.getBytes(StandardCharsets.UTF_8), new MessageProperties());

            assertThatCode(() -> listener.onOrderPaid(message)).doesNotThrowAnyException();
            verifyNoInteractions(recordPayment);
        }

        @Test
        @DisplayName("Body không phải JSON")
        void body_khong_phai_json() {
            assertThatCode(() -> listener.onOrderPaid(message("{oops"))).doesNotThrowAnyException();
            verifyNoInteractions(recordPayment);
        }

        @Test
        @DisplayName("Thiếu orderId, organizationId hoặc paidAt")
        void thieu_truong() {
            listener.onOrderPaid(message(payload(null, ORG.toString(), "2026-09-09T03:00:00Z", "1", "0")));
            listener.onOrderPaid(message(payload(ORDER.toString(), null, "2026-09-09T03:00:00Z", "1", "0")));
            listener.onOrderPaid(message(payload(ORDER.toString(), ORG.toString(), null, "1", "0")));

            verifyNoInteractions(recordPayment);
        }

        @Test
        @DisplayName("paidAt sai định dạng: không đoán một mốc thời gian")
        void paid_at_sai() {
            assertThatCode(() -> listener.onOrderPaid(
                            message(payload(ORDER.toString(), ORG.toString(), "09/09/2026", "1", "0"))))
                    .doesNotThrowAnyException();
            verifyNoInteractions(recordPayment);
        }

        @Test
        @DisplayName("orderId không phải UUID: ack, không ném — nếu không sẽ chặn cả hàng đợi tuần tự")
        void order_id_khong_phai_uuid() {
            assertThatCode(() -> listener.onOrderPaid(
                            message(payload("khong-phai-uuid", ORG.toString(), "2026-09-09T03:00:00Z", "1000", "50"))))
                    .doesNotThrowAnyException();
            verifyNoInteractions(recordPayment);
        }

        @Test
        @DisplayName("totalVnd âm: ack, không ném")
        void tong_am() {
            assertThatCode(() -> listener.onOrderPaid(
                            message(payload(ORDER.toString(), ORG.toString(), "2026-09-09T03:00:00Z", "-1000", "0"))))
                    .doesNotThrowAnyException();
            verifyNoInteractions(recordPayment);
        }
    }
}
