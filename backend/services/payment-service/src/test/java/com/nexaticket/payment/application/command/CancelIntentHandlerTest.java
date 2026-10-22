// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static com.nexaticket.payment.support.IntentFixtures.CLOCK;
import static com.nexaticket.payment.support.IntentFixtures.NOW;
import static com.nexaticket.payment.support.IntentFixtures.ORDER_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.payment.support.IntentFixtures;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Bù trừ của saga: chạy lại nhiều lần, không có gì để huỷ cũng là thành công, đã nhận tiền thì không huỷ. */
@ExtendWith(MockitoExtension.class)
class CancelIntentHandlerTest {

    @Mock
    PaymentIntentRepository intents;

    @Mock
    PayosGateway payos;

    CancelIntentHandler handler;

    UUID orderId;

    @BeforeEach
    void setUp() {
        handler = new CancelIntentHandler(intents, payos, CLOCK);
        orderId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Đường thường")
    class DuongThuong {

        @Test
        @DisplayName("Intent PENDING: huỷ, ghi mốc huỷ từ Clock, đóng đúng link payOS của nó")
        void huy_va_dong_link() {
            PaymentIntent intent = IntentFixtures.pending(orderId, ORDER_CODE);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(intent));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);

            assertThat(handler.handle(orderId)).isTrue();

            assertThat(intent.status()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(intent.cancelledAt()).isEqualTo(NOW);
            verify(payos).cancelPaymentLink(ORDER_CODE, "Đơn hàng đã đóng trước khi thanh toán");
        }
    }

    @Nested
    @DisplayName("Không có gì để làm")
    class KhongCoGiDeLam {

        @Test
        @DisplayName("Không có intent: false, không ném — saga có thể bù trừ việc chưa xảy ra")
        void khong_co_intent() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());

            assertThat(handler.handle(orderId)).isFalse();
            verifyNoInteractions(payos);
        }

        @Test
        @DisplayName("Đã nhận tiền: false, không ghi gì, không đóng link")
        void da_nhan_tien() {
            when(intents.findByOrder(orderId))
                    .thenReturn(Optional.of(IntentFixtures.confirmed(orderId, ORDER_CODE, "TF1")));

            assertThat(handler.handle(orderId)).isFalse();
            verify(intents, never()).updateStatusIfPending(any());
            verifyNoInteractions(payos);
        }

        @Test
        @DisplayName("Đã huỷ từ trước: false, không gọi payOS lần hai")
        void da_huy_tu_truoc() {
            when(intents.findByOrder(orderId))
                    .thenReturn(Optional.of(IntentFixtures.closed(orderId, ORDER_CODE, PaymentStatus.CANCELLED)));

            assertThat(handler.handle(orderId)).isFalse();
            verify(intents, never()).updateStatusIfPending(any());
            verifyNoInteractions(payos);
        }

        @Test
        @DisplayName("Thua cuộc đua ghi (tiến trình khác vừa đóng/ xác nhận): false, không đóng link")
        void thua_cuoc_dua_ghi() {
            PaymentIntent intent = IntentFixtures.pending(orderId, ORDER_CODE);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(intent));
            when(intents.updateStatusIfPending(intent)).thenReturn(false);

            assertThat(handler.handle(orderId)).isFalse();
            // Nếu bên thắng là một webhook vừa xác nhận tiền thì đóng link ở đây là sai hoàn toàn.
            verifyNoInteractions(payos);
        }

        @Test
        @DisplayName("Intent thời SePay (không có link payOS): vẫn huỷ, KHÔNG gọi payOS với mã 0")
        void intent_cu_khong_co_link() {
            PaymentIntent intent = IntentFixtures.legacy(orderId);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(intent));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);

            assertThat(handler.handle(orderId)).isTrue();
            verifyNoInteractions(payos);
        }
    }

    @Nested
    @DisplayName("Phục hồi khi payOS lỗi")
    class PhucHoi {

        @Test
        @DisplayName("payOS từ chối đóng link: vẫn true — intent đã CANCELLED, bước bù trừ không được coi là hỏng")
        void payos_tu_choi_dong_link() {
            PaymentIntent intent = IntentFixtures.pending(orderId, ORDER_CODE);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(intent));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            doThrow(new PayosGateway.Rejected("101", "Link đã thanh toán"))
                    .when(payos)
                    .cancelPaymentLink(anyLong(), anyString());

            assertThat(handler.handle(orderId)).isTrue();
            assertThat(intent.status()).isEqualTo(PaymentStatus.CANCELLED);
        }

        @Test
        @DisplayName("payOS không phản hồi khi đóng link: không ném ra ngoài")
        void payos_khong_phan_hoi() {
            PaymentIntent intent = IntentFixtures.pending(orderId, ORDER_CODE);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(intent));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            doThrow(new PayosGateway.Unavailable("timeout", null))
                    .when(payos)
                    .cancelPaymentLink(anyLong(), anyString());

            assertThatCode(() -> handler.handle(orderId)).doesNotThrowAnyException();
        }
    }
}
