// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.payment.domain.port.PayosGateway.WebhookPayment;
import com.nexaticket.payment.domain.port.PayosGateway.WebhookVerification;
import com.nexaticket.payment.domain.port.WebhookLog;
import com.nexaticket.platform.web.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cửa vào của webhook payOS: chữ ký sai thì KHÔNG 2xx, đã ký thì LUÔN 2xx trừ khi Ordering sập.
 *
 * <p>Phép kiểm chữ ký thật nằm ở adapter và có test riêng; ở đây cổng payOS là mock để điều khiển được
 * từng kết quả kiểm và khẳng định handler làm gì với nó.
 */
@ExtendWith(MockitoExtension.class)
class HandlePayosWebhookHandlerTest {

    private static final String RAW = "{\"code\":\"00\",\"data\":{\"orderCode\":1000001}}";

    @Mock
    PayosGateway payos;

    @Mock
    ConfirmTransferHandler confirmTransfer;

    @Mock
    WebhookLog webhookLog;

    HandlePayosWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HandlePayosWebhookHandler(payos, confirmTransfer, webhookLog);
    }

    private WebhookLog.Entry loggedEntry() {
        ArgumentCaptor<WebhookLog.Entry> captor = ArgumentCaptor.forClass(WebhookLog.Entry.class);
        verify(webhookLog).record(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Xác thực")
    class XacThuc {

        @Test
        @DisplayName("Chữ ký sai: authentic=false, ghi vết REJECTED, KHÔNG đi tới bước xác nhận tiền")
        void chu_ky_sai_thi_khong_xac_nhan() {
            when(payos.verifyWebhook(RAW)).thenReturn(WebhookVerification.rejected("Chữ ký không khớp"));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome.authentic()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("REJECTED");
            assertThat(outcome.note()).isEqualTo("Chữ ký không khớp");
            verifyNoInteractions(confirmTransfer);

            WebhookLog.Entry entry = loggedEntry();
            assertThat(entry.outcome()).isEqualTo("REJECTED");
            assertThat(entry.provider()).isEqualTo("PAYOS");
            // Payload thô KHÔNG vào cột jsonb ở nhánh này — có thể không phải JSON và làm vỡ INSERT.
            assertThat(entry.rawJson()).isNull();
            assertThat(entry.note()).contains("Chữ ký không khớp").contains(RAW.length() + " byte");
        }

        @Test
        @DisplayName("Body null: vẫn ghi vết với độ dài 0, không NullPointerException")
        void body_null_van_ghi_vet() {
            when(payos.verifyWebhook(null)).thenReturn(WebhookVerification.rejected("Body không phải JSON"));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(null);

            assertThat(outcome.authentic()).isFalse();
            assertThat(loggedEntry().note()).contains("0 byte");
        }
    }

    @Nested
    @DisplayName("Đã ký nhưng không có gì để xác nhận")
    class KhongCoGiDeXacNhan {

        @Test
        @DisplayName("payOS báo giao dịch KHÔNG thành công: nhận (200), ghi vết kèm mã payOS, không xác nhận")
        void giao_dich_that_bai() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(
                            new WebhookPayment(1_000_001L, 3_000_000L, "TF1", false, "01", "Giao dịch thất bại")));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome.authentic()).isTrue();
            assertThat(outcome.outcome()).isEqualTo("REJECTED");
            assertThat(outcome.note()).contains("[01]").contains("Giao dịch thất bại");
            verifyNoInteractions(confirmTransfer);

            WebhookLog.Entry entry = loggedEntry();
            assertThat(entry.payosOrderCode()).isEqualTo(1_000_001L);
            assertThat(entry.amountVnd()).isEqualTo(3_000_000L);
            assertThat(entry.rawJson()).isEqualTo(RAW);
        }

        @Test
        @DisplayName("Thiếu mã giao dịch: KHÔNG bịa khoá chống trùng, không xác nhận")
        void thieu_ma_giao_dich() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(
                            new WebhookPayment(1_000_001L, 3_000_000L, null, true, "00", "success")));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome.authentic()).isTrue();
            assertThat(outcome.outcome()).isEqualTo("REJECTED");
            assertThat(outcome.note()).contains("mã giao dịch");
            verifyNoInteractions(confirmTransfer);
            assertThat(loggedEntry().providerTxnId()).isNull();
        }

        @Test
        @DisplayName("orderCode 0 và amount 0: nhật ký ghi NULL chứ không ghi số 0 giả")
        void thieu_order_code_va_so_tien() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(new WebhookPayment(0, 0, "TF1", true, "00", "")));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome.outcome()).isEqualTo("REJECTED");
            verifyNoInteractions(confirmTransfer);
            WebhookLog.Entry entry = loggedEntry();
            assertThat(entry.payosOrderCode()).isNull();
            assertThat(entry.amountVnd()).isNull();
        }

        @Test
        @DisplayName("Số tiền âm trong payload đã ký: coi như thiếu, không xác nhận")
        void so_tien_am() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(
                            new WebhookPayment(1_000_001L, -3_000_000L, "TF1", true, "00", "")));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome.outcome()).isEqualTo("REJECTED");
            verifyNoInteractions(confirmTransfer);
        }
    }

    @Nested
    @DisplayName("Đường xác nhận")
    class DuongXacNhan {

        @Test
        @DisplayName("Webhook đủ trường: chuyển nguyên orderCode, số tiền, mã giao dịch và payload thô xuống")
        void chuyen_nguyen_du_lieu_xuong() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(
                            new WebhookPayment(1_000_001L, 3_000_000L, "TF1", true, "00", "success")));
            when(confirmTransfer.handle(any())).thenReturn(new ConfirmTransferHandler.Result("CONFIRMED", true, null));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome).isEqualTo(new HandlePayosWebhookHandler.Outcome(true, "CONFIRMED", null));
            ArgumentCaptor<ConfirmTransferHandler.Command> captor =
                    ArgumentCaptor.forClass(ConfirmTransferHandler.Command.class);
            verify(confirmTransfer).handle(captor.capture());
            assertThat(captor.getValue())
                    .isEqualTo(new ConfirmTransferHandler.Command("PAYOS", "TF1", 1_000_001L, 3_000_000L, RAW));
            // Nhật ký của nhánh này do ConfirmTransferHandler ghi; ghi thêm ở đây là hai dòng cho một lần.
            verifyNoInteractions(webhookLog);
        }

        @Test
        @DisplayName("Kết quả từ chối nghiệp vụ (AMOUNT_MISMATCH) vẫn là authentic — controller trả 200")
        void tu_choi_nghiep_vu_van_authentic() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(
                            new WebhookPayment(1_000_001L, 10_000L, "TF1", true, "00", "success")));
            when(confirmTransfer.handle(any()))
                    .thenReturn(new ConfirmTransferHandler.Result("AMOUNT_MISMATCH", true, "thiếu"));

            HandlePayosWebhookHandler.Outcome outcome = handler.handle(RAW);

            assertThat(outcome.authentic()).isTrue();
            assertThat(outcome.outcome()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(outcome.note()).isEqualTo("thiếu");
        }

        @Test
        @DisplayName("Ordering sập: lỗi 503 bay thẳng lên, không bị nuốt thành 200")
        void ordering_sap_thi_loi_bay_len() {
            when(payos.verifyWebhook(RAW))
                    .thenReturn(WebhookVerification.authentic(
                            new WebhookPayment(1_000_001L, 3_000_000L, "TF1", true, "00", "success")));
            when(confirmTransfer.handle(any()))
                    .thenThrow(new ApiException(PaymentErrorCode.ORDERING_UNAVAILABLE, "down"));

            assertThatThrownBy(() -> handler.handle(RAW))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PaymentErrorCode.ORDERING_UNAVAILABLE));
        }
    }
}
