// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static com.nexaticket.payment.support.IntentFixtures.AMOUNT;
import static com.nexaticket.payment.support.IntentFixtures.CLOCK;
import static com.nexaticket.payment.support.IntentFixtures.NOW;
import static com.nexaticket.payment.support.IntentFixtures.ORDER_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.port.OrderingPort;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.WebhookLog;
import com.nexaticket.payment.support.IntentFixtures;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Đối chiếu một lần báo có — chỗ tiền thật gặp phần mềm.
 *
 * <p>Mọi cổng ra đều là mock: test này canh <b>quyết định</b> của handler (có báo Ordering không, ghi
 * nhật ký gì, trả "thôi giao lại" hay ném), còn việc câu SQL có đúng không thì {@code PayosPaymentFlowIT}
 * canh trên PostgreSQL thật.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmTransferHandlerTest {

    private static final String PROVIDER = "PAYOS";
    private static final String TXN = "TF230204212323";
    private static final String RAW = "{\"raw\":true}";

    @Mock
    PaymentIntentRepository intents;

    @Mock
    OrderingPort ordering;

    @Mock
    WebhookLog webhookLog;

    ConfirmTransferHandler handler;

    UUID orderId;

    @BeforeEach
    void setUp() {
        handler = new ConfirmTransferHandler(intents, ordering, webhookLog, CLOCK);
        orderId = UUID.randomUUID();
    }

    private ConfirmTransferHandler.Command command(long amountVnd) {
        return new ConfirmTransferHandler.Command(PROVIDER, TXN, ORDER_CODE, amountVnd, RAW);
    }

    private PaymentIntent givenIntent(PaymentIntent intent) {
        when(intents.findByPayosOrderCode(ORDER_CODE)).thenReturn(Optional.of(intent));
        when(intents.orderOfTransaction(PROVIDER, TXN)).thenReturn(Optional.empty());
        return intent;
    }

    private WebhookLog.Entry loggedEntry() {
        ArgumentCaptor<WebhookLog.Entry> captor = ArgumentCaptor.forClass(WebhookLog.Entry.class);
        verify(webhookLog).record(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Đường thường")
    class DuongThuong {

        @Test
        @DisplayName("Trả đủ: ghi CONFIRMED, báo Ordering đúng đơn, nhật ký mang đủ dữ kiện")
        void tra_du_thi_xac_nhan_va_bao_ordering() {
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            when(ordering.confirmPayment(orderId)).thenReturn(OrderingPort.Confirmation.PAID);

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("CONFIRMED");
            assertThat(result.accepted()).isTrue();
            assertThat(result.note()).isNull();
            assertThat(intent.status()).isEqualTo(PaymentStatus.CONFIRMED);
            // Thời điểm xác nhận lấy từ Clock được tiêm, không từ đồng hồ hệ thống.
            assertThat(intent.confirmedAt()).isEqualTo(NOW);
            verify(ordering).confirmPayment(orderId);

            WebhookLog.Entry entry = loggedEntry();
            assertThat(entry.outcome()).isEqualTo("CONFIRMED");
            assertThat(entry.provider()).isEqualTo(PROVIDER);
            assertThat(entry.providerTxnId()).isEqualTo(TXN);
            assertThat(entry.payosOrderCode()).isEqualTo(ORDER_CODE);
            assertThat(entry.reference()).isEqualTo("NT1000001");
            assertThat(entry.amountVnd()).isEqualTo(AMOUNT);
            assertThat(entry.rawJson()).isEqualTo(RAW);
        }

        @Test
        @DisplayName("Trả THỪA: vẫn xác nhận và ghi đúng số đã trả, không phải số của đơn")
        void tra_thua_van_xac_nhan() {
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            when(ordering.confirmPayment(orderId)).thenReturn(OrderingPort.Confirmation.PAID);

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT + 1));

            assertThat(result.outcome()).isEqualTo("CONFIRMED");
            assertThat(intent.paidAmountVnd()).isEqualTo(AMOUNT + 1);
            assertThat(loggedEntry().amountVnd()).isEqualTo(AMOUNT + 1);
        }

        @Test
        @DisplayName("Ordering báo ALREADY_PAID: vẫn là CONFIRMED, không có ghi chú đối soát")
        void ordering_da_paid_tu_truoc() {
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            when(ordering.confirmPayment(orderId)).thenReturn(OrderingPort.Confirmation.ALREADY_PAID);

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("CONFIRMED");
            assertThat(result.note()).isNull();
            assertThat(loggedEntry().note()).isNull();
        }
    }

    @Nested
    @DisplayName("Tiền vào muộn")
    class TienVaoMuon {

        @Test
        @DisplayName("Ordering chuyển MANUAL_REVIEW: nhật ký CONFIRMED kèm ghi chú để người đối soát tìm ra")
        void ordering_manual_review_thi_ghi_chu() {
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            when(ordering.confirmPayment(orderId)).thenReturn(OrderingPort.Confirmation.MANUAL_REVIEW);

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("CONFIRMED");
            assertThat(result.accepted()).isTrue();
            assertThat(result.note()).contains("MANUAL_REVIEW");
            WebhookLog.Entry entry = loggedEntry();
            assertThat(entry.outcome()).isEqualTo("CONFIRMED");
            assertThat(entry.note()).contains("MANUAL_REVIEW");
        }

        @Test
        @DisplayName("Intent đã HẾT HẠN: NOT_PENDING, không báo Ordering, không ghi đè trạng thái")
        void intent_het_han_thi_khong_xac_nhan() {
            PaymentIntent intent = givenIntent(IntentFixtures.closed(orderId, ORDER_CODE, PaymentStatus.EXPIRED));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("NOT_PENDING");
            assertThat(result.accepted()).isTrue();
            assertThat(result.note()).contains("EXPIRED");
            assertThat(intent.status()).isEqualTo(PaymentStatus.EXPIRED);
            verify(intents, never()).updateStatusIfPending(any());
            verifyNoInteractions(ordering);
            assertThat(loggedEntry().outcome()).isEqualTo("NOT_PENDING");
        }

        @Test
        @DisplayName("Intent đã HUỶ: NOT_PENDING, không báo Ordering")
        void intent_da_huy_thi_khong_xac_nhan() {
            givenIntent(IntentFixtures.closed(orderId, ORDER_CODE, PaymentStatus.CANCELLED));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("NOT_PENDING");
            assertThat(result.note()).contains("CANCELLED");
            verifyNoInteractions(ordering);
        }

        @Test
        @DisplayName("Thua cuộc đua với job hết hạn: NOT_PENDING, KHÔNG báo Ordering")
        void thua_cuoc_dua_voi_job_het_han() {
            // Đọc thấy PENDING, nhưng câu UPDATE có điều kiện không ghi được: job quét hết hạn đã đóng
            // intent giữa lúc đọc và lúc ghi. Báo Ordering ở đây là phát vé cho một đơn đã đóng.
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));
            when(intents.updateStatusIfPending(intent)).thenReturn(false);

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("NOT_PENDING");
            assertThat(result.accepted()).isTrue();
            verifyNoInteractions(ordering);
            assertThat(loggedEntry().outcome()).isEqualTo("NOT_PENDING");
        }
    }

    @Nested
    @DisplayName("Từ chối có lý do")
    class TuChoi {

        @Test
        @DisplayName("orderCode không thuộc intent nào: UNKNOWN_REFERENCE, nhật ký không có reference")
        void order_code_la() {
            when(intents.findByPayosOrderCode(ORDER_CODE)).thenReturn(Optional.empty());

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("UNKNOWN_REFERENCE");
            assertThat(result.accepted()).isTrue();
            WebhookLog.Entry entry = loggedEntry();
            assertThat(entry.reference()).isNull();
            // Mã link vẫn phải có trong nhật ký — đúng những lần không khớp mới là lúc cần nó nhất.
            assertThat(entry.payosOrderCode()).isEqualTo(ORDER_CODE);
            verifyNoInteractions(ordering);
        }

        @Test
        @DisplayName("Trả thiếu MỘT đồng: AMOUNT_MISMATCH, intent vẫn PENDING, không báo Ordering")
        void tra_thieu_mot_dong() {
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT - 1));

            assertThat(result.outcome()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(result.accepted()).isTrue();
            assertThat(result.note()).contains(String.valueOf(AMOUNT - 1)).contains(String.valueOf(AMOUNT));
            assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
            verify(intents, never()).updateStatusIfPending(any());
            verifyNoInteractions(ordering);
        }

        @Test
        @DisplayName("Mã giao dịch đã ghi nhận cho ĐƠN KHÁC: DUPLICATE, không chạm vào intent này")
        void ma_giao_dich_cua_don_khac() {
            UUID otherOrder = UUID.randomUUID();
            PaymentIntent intent = IntentFixtures.pending(orderId, ORDER_CODE);
            when(intents.findByPayosOrderCode(ORDER_CODE)).thenReturn(Optional.of(intent));
            when(intents.orderOfTransaction(PROVIDER, TXN)).thenReturn(Optional.of(otherOrder));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("DUPLICATE");
            assertThat(result.note()).contains(otherOrder.toString());
            assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
            verify(intents, never()).updateStatusIfPending(any());
            verifyNoInteractions(ordering);
        }

        @Test
        @DisplayName("Mã giao dịch đã ghi nhận cho CHÍNH đơn này: đi tiếp vào domain và ra DUPLICATE")
        void ma_giao_dich_cua_chinh_don_nay() {
            when(intents.findByPayosOrderCode(ORDER_CODE))
                    .thenReturn(Optional.of(IntentFixtures.confirmed(orderId, ORDER_CODE, TXN)));
            when(intents.orderOfTransaction(PROVIDER, TXN)).thenReturn(Optional.of(orderId));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("DUPLICATE");
            assertThat(result.accepted()).isTrue();
            assertThat(loggedEntry().outcome()).isEqualTo("DUPLICATE");
            verifyNoInteractions(ordering);
        }

        @Test
        @DisplayName("Webhook giao lại lần hai: DUPLICATE, KHÔNG báo Ordering lần nữa")
        void webhook_giao_lai_khong_xac_nhan_hai_lan() {
            givenIntent(IntentFixtures.confirmed(orderId, ORDER_CODE, TXN));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("DUPLICATE");
            verify(intents, never()).updateStatusIfPending(any());
            verifyNoInteractions(ordering);
        }

        @Test
        @DisplayName("Giao dịch KHÁC trên đơn đã nhận tiền: DUPLICATE kèm mã giao dịch cũ để đối soát tay")
        void giao_dich_thu_hai_tren_don_da_tra() {
            givenIntent(IntentFixtures.confirmed(orderId, ORDER_CODE, "TF-CU"));

            ConfirmTransferHandler.Result result = handler.handle(command(AMOUNT));

            assertThat(result.outcome()).isEqualTo("DUPLICATE");
            assertThat(result.note()).contains("TF-CU").contains("đối soát tay");
            verifyNoInteractions(ordering);
        }
    }

    @Nested
    @DisplayName("Phục hồi khi lỗi")
    class PhucHoi {

        @Test
        @DisplayName("Ordering không phản hồi: ném ORDERING_UNAVAILABLE (503) để rollback và để payOS giao lại")
        void ordering_khong_phan_hoi_thi_nem_503() {
            PaymentIntent intent = givenIntent(IntentFixtures.pending(orderId, ORDER_CODE));
            when(intents.updateStatusIfPending(intent)).thenReturn(true);
            when(ordering.confirmPayment(orderId))
                    .thenThrow(new OrderingPort.OrderingUnavailableException("timeout", null));

            assertThatThrownBy(() -> handler.handle(command(AMOUNT))).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.ORDERING_UNAVAILABLE);
                assertThat(e.errorCode().httpStatus()).isEqualTo(503);
            });
            // Không ghi CONFIRMED trước khi Ordering đã biết — dòng đó sẽ nói dối nếu transaction không
            // rollback kịp (ví dụ handler bị gọi ngoài proxy).
            verify(webhookLog, never()).record(any());
        }
    }
}
