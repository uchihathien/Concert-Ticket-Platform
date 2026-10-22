// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.command.HandlePayosWebhookHandler;
import com.nexaticket.payment.support.WebSlice;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import com.nexaticket.platform.web.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Endpoint webhook payOS qua chuỗi filter thật: công khai (không JWT), 401 khi chữ ký sai, 200 cho mọi
 * thứ đã xử lý, 503 chỉ khi Ordering sập.
 */
@WebMvcTest(controllers = PayosWebhookController.class)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import(WebSlice.class)
class PayosWebhookControllerWebTest {

    private static final String PATH = "/api/billing/bank/webhook/payos";
    private static final String BODY = "{\"code\":\"00\",\"data\":{\"amount\":3000.00},\"signature\":\"abc\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HandlePayosWebhookHandler handler;

    @Nested
    @DisplayName("Công khai")
    class CongKhai {

        @Test
        @DisplayName("Không có JWT vẫn tới được handler — payOS không có token của ta")
        void khong_jwt_van_toi_handler() throws Exception {
            when(handler.handle(any())).thenReturn(new HandlePayosWebhookHandler.Outcome(true, "CONFIRMED", null));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                    .andExpect(jsonPath("$.note").doesNotExist());
        }

        @Test
        @DisplayName("Body chuyển xuống NGUYÊN VĂN từng byte — số 3000.00 không bị viết lại")
        void body_nguyen_van() throws Exception {
            when(handler.handle(any())).thenReturn(new HandlePayosWebhookHandler.Outcome(true, "CONFIRMED", null));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY));

            verify(handler).handle(BODY);
        }

        @Test
        @DisplayName("Endpoint khác cùng service vẫn đòi đăng nhập: 401")
        void endpoint_khac_van_doi_dang_nhap() throws Exception {
            mvc.perform(get("/v1/payments")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Mã trả về")
    class MaTraVe {

        @Test
        @DisplayName("Chữ ký sai: 401 và CHỈ có outcome — không lộ lý do cho người đang dò khoá")
        void chu_ky_sai_401_khong_lo_ly_do() throws Exception {
            when(handler.handle(any()))
                    .thenReturn(new HandlePayosWebhookHandler.Outcome(false, "REJECTED", "Chữ ký không khớp"));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.outcome").value("REJECTED"))
                    .andExpect(jsonPath("$.note").doesNotExist());
        }

        @Test
        @DisplayName("Từ chối nghiệp vụ đã ký (trả thiếu): 200 kèm ghi chú — giao lại cũng không đổi được gì")
        void tu_choi_nghiep_vu_200() throws Exception {
            when(handler.handle(any()))
                    .thenReturn(new HandlePayosWebhookHandler.Outcome(true, "AMOUNT_MISMATCH", "Chuyển 10000đ"));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"))
                    .andExpect(jsonPath("$.note").value("Chuyển 10000đ"));
        }

        @Test
        @DisplayName("Ordering sập: 503 dạng RFC 7807 với mã ORDERING_UNAVAILABLE để payOS giao lại")
        void ordering_sap_503() throws Exception {
            when(handler.handle(any()))
                    .thenThrow(new ApiException(PaymentErrorCode.ORDERING_UNAVAILABLE, "Ordering unavailable"));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ORDERING_UNAVAILABLE"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/ordering-unavailable"))
                    .andExpect(jsonPath("$.title").value("ordering unavailable"));
        }

        @Test
        @DisplayName("Lỗi không lường trước: 500 không lộ thông điệp gốc")
        void loi_bat_ngo_500() throws Exception {
            when(handler.handle(any())).thenThrow(new IllegalStateException("jdbc:postgresql://db-noi-bo:5432"));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("postgresql"))));
        }
    }

    @Nested
    @DisplayName("Request hỏng")
    class RequestHong {

        @Test
        @DisplayName("Không có body: 400 VALIDATION_FAILED, không gọi handler")
        void khong_body() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(handler);
        }

        @Test
        @DisplayName("Content-Type không phải JSON: 415, không gọi handler")
        void sai_content_type() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(BODY))
                    .andExpect(status().isUnsupportedMediaType());
            verifyNoInteractions(handler);
        }

        @Test
        @DisplayName("GET vào endpoint webhook: 405, không gọi handler")
        void sai_method() throws Exception {
            mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
            verifyNoInteractions(handler);
        }
    }
}
