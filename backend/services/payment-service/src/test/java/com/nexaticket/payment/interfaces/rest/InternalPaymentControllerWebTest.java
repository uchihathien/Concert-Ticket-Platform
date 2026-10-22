// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.command.CancelIntentHandler;
import com.nexaticket.payment.application.command.ConfirmTransferHandler;
import com.nexaticket.payment.application.command.OpenIntentHandler;
import com.nexaticket.payment.application.command.ReconcileIntentHandler;
import com.nexaticket.payment.application.command.SimulateTransferHandler;
import com.nexaticket.payment.support.WebSlice;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Open Host Service cho saga checkout, chạy sau {@code InternalApiFilter} thật với bí mật nội bộ đã khai.
 *
 * <p>Thiếu hoặc sai {@code X-Internal-Token} phải là <b>404</b> — không phải 401, để không xác nhận rằng
 * đường dẫn tồn tại — và handler không được chạm tới. Đặc biệt quan trọng với {@code simulate-transfer}: nó
 * đánh dấu đơn đã trả tiền.
 */
@WebMvcTest(
        controllers = InternalPaymentController.class,
        properties = "nexaticket.internal.shared-secret=" + WebSlice.INTERNAL_SECRET)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import(WebSlice.class)
class InternalPaymentControllerWebTest {

    private static final String BASE = "/internal/payment-intents";
    private static final UUID ORDER = UUID.fromString("7a1c2f0e-1111-4222-8333-944455556666");
    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OpenIntentHandler openIntent;

    @MockitoBean
    CancelIntentHandler cancelIntent;

    @MockitoBean
    SimulateTransferHandler simulateTransfer;

    @MockitoBean
    ReconcileIntentHandler reconcileIntent;

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header("X-Internal-Token", WebSlice.INTERNAL_SECRET);
    }

    private static String openBody(String orderId, String organizationId, String amount, String expiresAt) {
        StringBuilder json = new StringBuilder("{");
        if (orderId != null) {
            json.append("\"orderId\":\"").append(orderId).append("\",");
        }
        if (organizationId != null) {
            json.append("\"organizationId\":\"").append(organizationId).append("\",");
        }
        if (amount != null) {
            json.append("\"amountVnd\":").append(amount).append(',');
        }
        if (expiresAt != null) {
            json.append("\"expiresAt\":\"").append(expiresAt).append("\",");
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static final String VALID_OPEN =
            openBody(ORDER.toString(), ORG.toString(), "3000000", "2026-09-09T03:15:00Z");

    @Nested
    @DisplayName("Bí mật nội bộ")
    class BiMatNoiBo {

        @Test
        @DisplayName("Không có X-Internal-Token: 404, handler không được gọi")
        void thieu_token_404() throws Exception {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_OPEN))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(openIntent);
        }

        @Test
        @DisplayName("Token sai: 404 cho cả giả lập chuyển khoản — đường phát vé miễn phí")
        void token_sai_404() throws Exception {
            mvc.perform(post(BASE + "/" + ORDER + "/simulate-transfer").header("X-Internal-Token", "doan-mo"))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(simulateTransfer);
        }

        @Test
        @DisplayName("Token sai với huỷ và đối soát: 404, không chạm handler")
        void token_sai_huy_va_doi_soat() throws Exception {
            mvc.perform(delete(BASE + "/" + ORDER).header("X-Internal-Token", WebSlice.INTERNAL_SECRET + "x"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(BASE + "/" + ORDER + "/reconcile")).andExpect(status().isNotFound());
            verifyNoInteractions(cancelIntent, reconcileIntent);
        }

        @Test
        @DisplayName("Một JWT người dùng hợp lệ KHÔNG thay được bí mật nội bộ")
        void jwt_khong_thay_duoc_bi_mat() throws Exception {
            mvc.perform(post(BASE + "/" + ORDER + "/simulate-transfer")
                            .header("Authorization", "Bearer " + WebSlice.VALID_TOKEN))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(simulateTransfer);
        }
    }

    @Nested
    @DisplayName("Mở intent")
    class MoIntent {

        @Test
        @DisplayName("Hợp lệ: chuyển đủ trường xuống handler, trả JSON đúng tên trường của hợp đồng với Ordering")
        void mo_intent_hop_le() throws Exception {
            when(openIntent.handle(any()))
                    .thenReturn(new OpenIntentHandler.Result(
                            "NT1000001", "000201qr", "https://pay.payos.vn/web/x", "970422", "V3CAS1", "NEXATICKET"));

            mvc.perform(internal(post(BASE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_OPEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentReference").value("NT1000001"))
                    .andExpect(jsonPath("$.vietQrPayload").value("000201qr"))
                    .andExpect(jsonPath("$.checkoutUrl").value("https://pay.payos.vn/web/x"))
                    .andExpect(jsonPath("$.bankBin").value("970422"))
                    .andExpect(jsonPath("$.bankAccountNumber").value("V3CAS1"))
                    .andExpect(jsonPath("$.bankAccountName").value("NEXATICKET"));

            ArgumentCaptor<OpenIntentHandler.Command> captor = ArgumentCaptor.forClass(OpenIntentHandler.Command.class);
            verify(openIntent).handle(captor.capture());
            assertThat(captor.getValue())
                    .isEqualTo(new OpenIntentHandler.Command(
                            ORDER, ORG, 3_000_000L, Instant.parse("2026-09-09T03:15:00Z")));
        }

        @Test
        @DisplayName("Thiếu orderId: 400 VALIDATION_FAILED chỉ ra đúng trường, không gọi handler")
        void thieu_order_id() throws Exception {
            mvc.perform(internal(post(BASE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(openBody(null, ORG.toString(), "3000000", "2026-09-09T03:15:00Z")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.meta.fields.orderId").exists());
            verifyNoInteractions(openIntent);
        }

        @Test
        @DisplayName("Thiếu organizationId và expiresAt: 400 liệt kê cả hai trường")
        void thieu_hai_truong() throws Exception {
            mvc.perform(internal(post(BASE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(openBody(ORDER.toString(), null, "3000000", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.organizationId").exists())
                    .andExpect(jsonPath("$.meta.fields.expiresAt").exists());
            verifyNoInteractions(openIntent);
        }

        @Test
        @DisplayName("Số tiền 0, âm hoặc vắng mặt: 400 — một intent 0 đồng là vé miễn phí")
        void so_tien_khong_duong() throws Exception {
            for (String amount : new String[] {"0", "-1", null}) {
                mvc.perform(internal(post(BASE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(openBody(ORDER.toString(), ORG.toString(), amount, "2026-09-09T03:15:00Z")))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.meta.fields.amountVnd").exists());
            }
            verifyNoInteractions(openIntent);
        }

        @Test
        @DisplayName("JSON hỏng, UUID sai khuôn, ngày sai khuôn: 400 'Malformed request body'")
        void body_hong() throws Exception {
            for (String body : new String[] {
                "{\"orderId\":",
                openBody("khong-phai-uuid", ORG.toString(), "1", "2026-09-09T03:15:00Z"),
                openBody(ORDER.toString(), ORG.toString(), "1", "hôm qua"),
                openBody(ORDER.toString(), ORG.toString(), "\"nhiều tiền\"", "2026-09-09T03:15:00Z")
            }) {
                mvc.perform(internal(post(BASE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.detail").value("Malformed request body"));
            }
            verifyNoInteractions(openIntent);
        }

        @Test
        @DisplayName("payOS không phản hồi: 503 PAYOS_UNAVAILABLE dạng RFC 7807")
        void payos_khong_phan_hoi() throws Exception {
            when(openIntent.handle(any()))
                    .thenThrow(new ApiException(PaymentErrorCode.PAYOS_UNAVAILABLE, "payOS unavailable, please retry"));

            mvc.perform(internal(post(BASE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_OPEN))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PAYOS_UNAVAILABLE"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/payos-unavailable"))
                    .andExpect(jsonPath("$.detail").value("payOS unavailable, please retry"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        @Test
        @DisplayName("payOS từ chối: 502 PAYOS_REJECTED")
        void payos_tu_choi() throws Exception {
            when(openIntent.handle(any()))
                    .thenThrow(new ApiException(PaymentErrorCode.PAYOS_REJECTED, "payOS rejected the payment link"));

            mvc.perform(internal(post(BASE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_OPEN))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("PAYOS_REJECTED"));
        }
    }

    @Nested
    @DisplayName("Huỷ, đối soát, giả lập")
    class HuyDoiSoatGiaLap {

        @Test
        @DisplayName("Huỷ: luôn 204, kể cả khi không có gì để huỷ")
        void huy_luon_204() throws Exception {
            when(cancelIntent.handle(ORDER)).thenReturn(false);

            mvc.perform(internal(delete(BASE + "/" + ORDER))).andExpect(status().isNoContent());

            verify(cancelIntent).handle(ORDER);
        }

        @Test
        @DisplayName("orderId trên đường dẫn sai khuôn UUID: 400, không gọi handler")
        void order_id_sai_khuon() throws Exception {
            mvc.perform(internal(delete(BASE + "/khong-phai-uuid"))).andExpect(status().isBadRequest());
            verifyNoInteractions(cancelIntent);
        }

        @Test
        @DisplayName("Đối soát: trả outcome và note của handler")
        void doi_soat() throws Exception {
            when(reconcileIntent.handle(ORDER))
                    .thenReturn(new ConfirmTransferHandler.Result("NOT_PAID", true, "payOS đang ở trạng thái PENDING"));

            mvc.perform(internal(post(BASE + "/" + ORDER + "/reconcile")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("NOT_PAID"))
                    .andExpect(jsonPath("$.note").value("payOS đang ở trạng thái PENDING"));
        }

        @Test
        @DisplayName("Đối soát đơn không có intent: 404 PAYMENT_INTENT_NOT_FOUND")
        void doi_soat_khong_co_intent() throws Exception {
            when(reconcileIntent.handle(ORDER))
                    .thenThrow(new ApiException(PaymentErrorCode.PAYMENT_INTENT_NOT_FOUND, "No payment intent"));

            mvc.perform(internal(post(BASE + "/" + ORDER + "/reconcile")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("Giả lập không có body: trả đủ, mã tự sinh — handler nhận (orderId, null, null)")
        void gia_lap_khong_body() throws Exception {
            when(simulateTransfer.handle(any())).thenReturn(new ConfirmTransferHandler.Result("CONFIRMED", true, null));

            mvc.perform(internal(post(BASE + "/" + ORDER + "/simulate-transfer")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("CONFIRMED"));

            verify(simulateTransfer).handle(new SimulateTransferHandler.Command(ORDER, null, null));
        }

        @Test
        @DisplayName("Giả lập có body: chuyển nguyên số tiền và mã giao dịch")
        void gia_lap_co_body() throws Exception {
            when(simulateTransfer.handle(any()))
                    .thenReturn(new ConfirmTransferHandler.Result("AMOUNT_MISMATCH", true, "thiếu"));

            mvc.perform(internal(post(BASE + "/" + ORDER + "/simulate-transfer"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountVnd\":10000,\"providerTxnId\":\"SBX-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));

            verify(simulateTransfer).handle(new SimulateTransferHandler.Command(ORDER, 10_000L, "SBX-1"));
        }

        @Test
        @DisplayName("Sandbox tắt: 403 SANDBOX_DISABLED")
        void sandbox_tat_403() throws Exception {
            when(simulateTransfer.handle(any()))
                    .thenThrow(new ApiException(PaymentErrorCode.SANDBOX_DISABLED, "Sandbox transfers are disabled"));

            mvc.perform(internal(post(BASE + "/" + ORDER + "/simulate-transfer")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SANDBOX_DISABLED"));
        }
    }
}
