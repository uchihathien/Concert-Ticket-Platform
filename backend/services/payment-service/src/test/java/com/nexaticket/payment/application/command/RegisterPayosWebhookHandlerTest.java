// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.platform.web.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Đăng ký URL webhook với payOS — lệnh tường minh, nổ to khi thiếu URL hoặc payOS từ chối. */
@ExtendWith(MockitoExtension.class)
class RegisterPayosWebhookHandlerTest {

    private static final String CONFIGURED = "https://api.nexaticket.vn/api/billing/bank/webhook/payos";

    @Mock
    PayosGateway payos;

    private RegisterPayosWebhookHandler handler(String configuredUrl) {
        return new RegisterPayosWebhookHandler(
                payos,
                new PaymentProperties(
                        false, new PaymentProperties.Payos(null, "c", "a", "k", null, configuredUrl, null, null)));
    }

    @Test
    @DisplayName("Không truyền URL: dùng URL trong cấu hình và trả về tài khoản kênh để kiểm bằng mắt")
    void dung_url_cau_hinh() {
        when(payos.confirmWebhook(CONFIGURED))
                .thenReturn(new PayosGateway.WebhookRegistration(CONFIGURED, "V3CAS0001", "NEXATICKET"));

        RegisterPayosWebhookHandler.Result result = handler(CONFIGURED).handle(null);

        assertThat(result).isEqualTo(new RegisterPayosWebhookHandler.Result(CONFIGURED, "V3CAS0001", "NEXATICKET"));
    }

    @Test
    @DisplayName("URL truyền vào trống trơn (khoảng trắng) cũng coi như không truyền")
    void url_khoang_trang_dung_cau_hinh() {
        when(payos.confirmWebhook(CONFIGURED))
                .thenReturn(new PayosGateway.WebhookRegistration(CONFIGURED, "V3CAS0001", "NEXATICKET"));

        handler(CONFIGURED).handle("   ");

        verify(payos).confirmWebhook(CONFIGURED);
    }

    @Test
    @DisplayName("URL truyền tường minh thắng URL cấu hình")
    void url_tuong_minh_thang() {
        String tunnel = "https://abc.trycloudflare.com/api/billing/bank/webhook/payos";
        when(payos.confirmWebhook(tunnel))
                .thenReturn(new PayosGateway.WebhookRegistration(tunnel, "V3CAS0001", "NEXATICKET"));

        assertThat(handler(CONFIGURED).handle(tunnel).webhookUrl()).isEqualTo(tunnel);
    }

    @Test
    @DisplayName("Không có URL ở đâu cả: PAYOS_REJECTED, KHÔNG gọi payOS")
    void khong_co_url() {
        assertThatThrownBy(() -> handler(" ").handle(null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYOS_REJECTED);
            assertThat(e.getMessage()).contains("webhook-url");
        });
        verifyNoInteractions(payos);
    }

    @Test
    @DisplayName("payOS từ chối URL: 502 kèm nguyên câu lỗi của payOS")
    void payos_tu_choi() {
        when(payos.confirmWebhook(CONFIGURED))
                .thenThrow(new PayosGateway.Rejected("20", "Webhook url không phải HTTPS"));

        assertThatThrownBy(() -> handler(CONFIGURED).handle(null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYOS_REJECTED);
            assertThat(e.getMessage()).contains("Webhook url không phải HTTPS");
        });
    }

    @Test
    @DisplayName("payOS không phản hồi: 503 PAYOS_UNAVAILABLE — thử lại được")
    void payos_khong_phan_hoi() {
        when(payos.confirmWebhook(CONFIGURED)).thenThrow(new PayosGateway.Unavailable("timeout", null));

        assertThatThrownBy(() -> handler(CONFIGURED).handle(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(PaymentErrorCode.PAYOS_UNAVAILABLE));
    }
}
