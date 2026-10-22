// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.interfaces.ws;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Chuỗi bảo mật của realtime-gateway.
 *
 * <p>Không có controller nào nên "đường được phép" được nhận ra bằng việc KHÔNG bị 401 (404 là
 * qua được bảo mật nhưng chưa có handler), còn "đường bị chặn" là 401.
 */
@WebMvcTest
@ContextConfiguration(classes = RealtimeSecurityConfig.class)
class RealtimeSecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    @DisplayName("/actuator/health không đòi xác thực — liveness probe không được nhận 401")
    void health_khong_dong_401() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("/ws/** mở cho khách vãng lai — handshake ẩn danh không bị chặn")
    void websocket_an_danh() throws Exception {
        mvc.perform(get("/ws/sessions/11111111-1111-4111-a111-111111111111")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("mọi đường khác đòi JWT: 401")
    void duong_khac_401() throws Exception {
        mvc.perform(get("/v1/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST không cần CSRF: chuỗi không giữ session cookie")
    void khong_csrf() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ws/sessions/x"))
                .andExpect(status().isNotFound());
    }
}
