// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static com.nexaticket.payment.support.IntentFixtures.AMOUNT;
import static com.nexaticket.payment.support.IntentFixtures.ORDER_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
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

/** Kéo trạng thái từ payOS về — đi qua đúng cùng đường đối chiếu với webhook, không tin payOS hơn. */
@ExtendWith(MockitoExtension.class)
class ReconcileIntentHandlerTest {

    @Mock
    PaymentIntentRepository intents;

    @Mock
    PayosGateway payos;

    @Mock
    ConfirmTransferHandler confirmTransfer;

    ReconcileIntentHandler handler;

    UUID orderId;

    @BeforeEach
    void setUp() {
        handler = new ReconcileIntentHandler(intents, payos, confirmTransfer);
        orderId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Đường thường")
    class DuongThuong {

        @Test
        @DisplayName("payOS báo PAID: chuyển số ĐÃ THU và mã giao dịch xuống cùng handler với webhook")
        void paid_thi_di_qua_confirm_transfer() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE))
                    .thenReturn(Optional.of(new PayosGateway.Settlement("PAID", AMOUNT, "FT123")));
            when(confirmTransfer.handle(any())).thenReturn(new ConfirmTransferHandler.Result("CONFIRMED", true, null));

            ConfirmTransferHandler.Result result = handler.handle(orderId);

            assertThat(result.outcome()).isEqualTo("CONFIRMED");
            ArgumentCaptor<ConfirmTransferHandler.Command> captor =
                    ArgumentCaptor.forClass(ConfirmTransferHandler.Command.class);
            verify(confirmTransfer).handle(captor.capture());
            ConfirmTransferHandler.Command cmd = captor.getValue();
            assertThat(cmd.provider()).isEqualTo("PAYOS");
            assertThat(cmd.providerTxnId()).isEqualTo("FT123");
            assertThat(cmd.payosOrderCode()).isEqualTo(ORDER_CODE);
            assertThat(cmd.amountVnd()).isEqualTo(AMOUNT);
            // Nhật ký phải nói rõ đây là đường đối soát, không phải một webhook nhận được.
            assertThat(cmd.rawJson()).contains("\"source\":\"reconcile\"").contains("FT123");
        }

        @Test
        @DisplayName("payOS báo PAID nhưng thu THIẾU: vẫn chuyển xuống để domain chặn, không tự quyết ở đây")
        void paid_nhung_thieu_tien() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE))
                    .thenReturn(Optional.of(new PayosGateway.Settlement("PAID", 10_000L, "FT9")));
            when(confirmTransfer.handle(any()))
                    .thenReturn(new ConfirmTransferHandler.Result("AMOUNT_MISMATCH", true, "thiếu"));

            assertThat(handler.handle(orderId).outcome()).isEqualTo("AMOUNT_MISMATCH");
        }
    }

    @Nested
    @DisplayName("Không có gì để ghi nhận")
    class KhongCoGi {

        @Test
        @DisplayName("Không có intent: 404 PAYMENT_INTENT_NOT_FOUND")
        void khong_co_intent() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.handle(orderId))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PaymentErrorCode.PAYMENT_INTENT_NOT_FOUND));
            verifyNoInteractions(payos, confirmTransfer);
        }

        @Test
        @DisplayName("Đã xác nhận: DUPLICATE, không hỏi payOS — gọi đối soát hai lần là vô hại")
        void da_xac_nhan() {
            when(intents.findByOrder(orderId))
                    .thenReturn(Optional.of(IntentFixtures.confirmed(orderId, ORDER_CODE, "FT1")));

            ConfirmTransferHandler.Result result = handler.handle(orderId);

            assertThat(result.outcome()).isEqualTo("DUPLICATE");
            assertThat(result.accepted()).isTrue();
            verifyNoInteractions(payos, confirmTransfer);
        }

        @Test
        @DisplayName("payOS không biết link: UNKNOWN_REFERENCE")
        void payos_khong_biet_link() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE)).thenReturn(Optional.empty());

            assertThat(handler.handle(orderId).outcome()).isEqualTo("UNKNOWN_REFERENCE");
            verifyNoInteractions(confirmTransfer);
        }

        @Test
        @DisplayName("payOS còn PENDING: NOT_PAID kèm trạng thái payOS trong ghi chú")
        void payos_con_pending() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE))
                    .thenReturn(Optional.of(new PayosGateway.Settlement("PENDING", 0, null)));

            ConfirmTransferHandler.Result result = handler.handle(orderId);

            assertThat(result.outcome()).isEqualTo("NOT_PAID");
            assertThat(result.note()).contains("PENDING");
            verifyNoInteractions(confirmTransfer);
        }

        @Test
        @DisplayName("payOS báo 'paid' viết thường: KHÔNG coi là đã thu — so khớp đúng chữ")
        void trang_thai_viet_thuong_khong_phai_paid() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE))
                    .thenReturn(Optional.of(new PayosGateway.Settlement("paid", AMOUNT, "FT1")));

            assertThat(handler.handle(orderId).outcome()).isEqualTo("NOT_PAID");
            verifyNoInteractions(confirmTransfer);
        }

        @Test
        @DisplayName("Intent thời SePay: không hỏi payOS với mã đơn 0")
        void intent_cu_khong_hoi_payos() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.legacy(orderId)));

            assertThat(handler.handle(orderId).outcome()).isEqualTo("UNKNOWN_REFERENCE");
            verifyNoInteractions(payos, confirmTransfer);
        }
    }

    @Nested
    @DisplayName("Lỗi từ payOS")
    class LoiTuPayos {

        @Test
        @DisplayName("payOS không phản hồi: 503 PAYOS_UNAVAILABLE")
        void payos_khong_phan_hoi() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE)).thenThrow(new PayosGateway.Unavailable("timeout", null));

            assertThatThrownBy(() -> handler.handle(orderId))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PaymentErrorCode.PAYOS_UNAVAILABLE));
        }

        @Test
        @DisplayName("Chữ ký response sai: lỗi bay lên nguyên vẹn, KHÔNG quy về 'chưa có gì'")
        void chu_ky_response_sai_bay_len() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(payos.fetchSettlement(ORDER_CODE))
                    .thenThrow(new PayosGateway.Rejected("SIGNATURE_MISMATCH", "Chữ ký response payOS không khớp"));

            assertThatThrownBy(() -> handler.handle(orderId))
                    .isInstanceOf(PayosGateway.Rejected.class)
                    .hasMessageContaining("không khớp");
            verifyNoInteractions(confirmTransfer);
        }
    }
}
