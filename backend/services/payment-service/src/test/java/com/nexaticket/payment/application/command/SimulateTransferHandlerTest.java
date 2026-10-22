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
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.support.IntentFixtures;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Giả lập chuyển khoản — phải KHÔNG THỂ chạy khi sandbox tắt, và phải đi qua đúng đường webhook thật. */
@ExtendWith(MockitoExtension.class)
class SimulateTransferHandlerTest {

    @Mock
    PaymentIntentRepository intents;

    @Mock
    ConfirmTransferHandler confirmTransfer;

    private final UUID orderId = UUID.randomUUID();

    private SimulateTransferHandler handler(boolean sandbox) {
        PaymentProperties properties = new PaymentProperties(
                sandbox, new PaymentProperties.Payos(null, null, null, null, null, null, null, null));
        return new SimulateTransferHandler(intents, confirmTransfer, properties);
    }

    @Nested
    @DisplayName("Chốt cấu hình")
    class ChotCauHinh {

        @Test
        @DisplayName("Sandbox tắt: 403 SANDBOX_DISABLED, không đọc intent, không chạm đường xác nhận")
        void sandbox_tat_thi_khong_the_gia_lap() {
            assertThatThrownBy(() -> handler(false).handle(new SimulateTransferHandler.Command(orderId, null, null)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.SANDBOX_DISABLED);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(403);
                    });
            verifyNoInteractions(intents, confirmTransfer);
        }

        @Test
        @DisplayName("Sandbox tắt: kể cả lệnh có mã giao dịch và số tiền đầy đủ cũng bị chặn")
        void sandbox_tat_lenh_day_du_cung_bi_chan() {
            assertThatThrownBy(() ->
                            handler(false).handle(new SimulateTransferHandler.Command(orderId, AMOUNT, "SBX-THU-CONG")))
                    .isInstanceOf(ApiException.class);
            verifyNoInteractions(intents, confirmTransfer);
        }
    }

    @Nested
    @DisplayName("Sandbox bật")
    class SandboxBat {

        @Test
        @DisplayName("Không truyền gì: trả đủ số của đơn, mã giao dịch tự sinh dạng SBX-, provider SANDBOX")
        void mac_dinh_tra_du() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(confirmTransfer.handle(any())).thenReturn(new ConfirmTransferHandler.Result("CONFIRMED", true, null));

            handler(true).handle(new SimulateTransferHandler.Command(orderId, null, "  "));

            ArgumentCaptor<ConfirmTransferHandler.Command> captor =
                    ArgumentCaptor.forClass(ConfirmTransferHandler.Command.class);
            verify(confirmTransfer).handle(captor.capture());
            ConfirmTransferHandler.Command cmd = captor.getValue();
            // SANDBOX, không bao giờ PAYOS: nhật ký phải phân biệt được tiền giả với tiền thật.
            assertThat(cmd.provider()).isEqualTo("SANDBOX");
            assertThat(cmd.providerTxnId()).matches("SBX-[0-9A-F-]{13}");
            assertThat(cmd.payosOrderCode()).isEqualTo(ORDER_CODE);
            assertThat(cmd.amountVnd()).isEqualTo(AMOUNT);
            assertThat(cmd.rawJson()).contains("\"simulated\":true").contains(orderId.toString());
        }

        @Test
        @DisplayName("Truyền số tiền và mã giao dịch: giữ nguyên để thử được nhánh thiếu tiền và webhook trùng")
        void truyen_so_tien_va_ma() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(confirmTransfer.handle(any()))
                    .thenReturn(new ConfirmTransferHandler.Result("AMOUNT_MISMATCH", true, "thiếu"));

            ConfirmTransferHandler.Result result =
                    handler(true).handle(new SimulateTransferHandler.Command(orderId, 10_000L, "SBX-CU"));

            assertThat(result.outcome()).isEqualTo("AMOUNT_MISMATCH");
            ArgumentCaptor<ConfirmTransferHandler.Command> captor =
                    ArgumentCaptor.forClass(ConfirmTransferHandler.Command.class);
            verify(confirmTransfer).handle(captor.capture());
            assertThat(captor.getValue().amountVnd()).isEqualTo(10_000L);
            assertThat(captor.getValue().providerTxnId()).isEqualTo("SBX-CU");
        }

        @Test
        @DisplayName("Hai lần giả lập không truyền mã: hai mã giao dịch khác nhau")
        void ma_tu_sinh_khong_trung() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(IntentFixtures.pending(orderId, ORDER_CODE)));
            when(confirmTransfer.handle(any())).thenReturn(new ConfirmTransferHandler.Result("CONFIRMED", true, null));
            SimulateTransferHandler handler = handler(true);

            handler.handle(new SimulateTransferHandler.Command(orderId, null, null));
            handler.handle(new SimulateTransferHandler.Command(orderId, null, null));

            ArgumentCaptor<ConfirmTransferHandler.Command> captor =
                    ArgumentCaptor.forClass(ConfirmTransferHandler.Command.class);
            verify(confirmTransfer, org.mockito.Mockito.times(2)).handle(captor.capture());
            assertThat(captor.getAllValues().get(0).providerTxnId())
                    .isNotEqualTo(captor.getAllValues().get(1).providerTxnId());
        }

        @Test
        @DisplayName("Không có intent: 404 PAYMENT_INTENT_NOT_FOUND")
        void khong_co_intent() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler(true).handle(new SimulateTransferHandler.Command(orderId, null, null)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PaymentErrorCode.PAYMENT_INTENT_NOT_FOUND));
            verifyNoInteractions(confirmTransfer);
        }
    }
}
