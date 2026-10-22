// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.command.RegisterPayosWebhookHandler;
import com.nexaticket.payment.support.WebSlice;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import com.nexaticket.platform.web.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Lệnh đăng ký webhook payOS — ghi cấu hình ở phía nhà cung cấp, nên chỉ trong mạng nội bộ có bí mật. */
@WebMvcTest(
        controllers = InternalPayosAdminController.class,
        properties = "nexaticket.internal.shared-secret=" + WebSlice.INTERNAL_SECRET)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import(WebSlice.class)
class InternalPayosAdminControllerWebTest {

    private static final String PATH = "/internal/payos/confirm-webhook";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RegisterPayosWebhookHandler registerWebhook;

    @Test
    @DisplayName("Không có bí mật nội bộ: 404 — không ai ngoài hệ thống trỏ được webhook production đi nơi khác")
    void khong_bi_mat_404() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\":\"https://ke-gian.example/hook\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(registerWebhook);
    }

    @Test
    @DisplayName("Không có body: dùng URL cấu hình (handler nhận null)")
    void khong_body() throws Exception {
        when(registerWebhook.handle(null))
                .thenReturn(new RegisterPayosWebhookHandler.Result("https://api/hook", "V3CAS0001", "NEXATICKET"));

        mvc.perform(post(PATH).header("X-Internal-Token", WebSlice.INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value("https://api/hook"))
                .andExpect(jsonPath("$.accountNumber").value("V3CAS0001"))
                .andExpect(jsonPath("$.accountName").value("NEXATICKET"));
    }

    @Test
    @DisplayName("Có webhookUrl: chuyển nguyên văn xuống handler")
    void co_url() throws Exception {
        when(registerWebhook.handle(any()))
                .thenReturn(new RegisterPayosWebhookHandler.Result("https://tunnel/hook", "V3CAS0001", "NEXATICKET"));

        mvc.perform(post(PATH)
                        .header("X-Internal-Token", WebSlice.INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\":\"https://tunnel/hook\"}"))
                .andExpect(status().isOk());

        verify(registerWebhook).handle("https://tunnel/hook");
    }

    @Test
    @DisplayName("payOS từ chối URL: 502 PAYOS_REJECTED kèm lý do")
    void payos_tu_choi() throws Exception {
        when(registerWebhook.handle(any()))
                .thenThrow(new ApiException(
                        PaymentErrorCode.PAYOS_REJECTED, "payOS rejected the webhook URL: không phải HTTPS"));

        mvc.perform(post(PATH)
                        .header("X-Internal-Token", WebSlice.INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\":\"http://localhost/hook\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYOS_REJECTED"))
                .andExpect(jsonPath("$.detail").value("payOS rejected the webhook URL: không phải HTTPS"));
    }

    @Test
    @DisplayName("Body JSON hỏng: 400, không gọi payOS")
    void body_hong() throws Exception {
        mvc.perform(post(PATH)
                        .header("X-Internal-Token", WebSlice.INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(registerWebhook);
    }
}
