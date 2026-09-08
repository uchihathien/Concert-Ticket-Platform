// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.interfaces.ws;

import com.nexaticket.realtime.application.SessionRegistry;
import com.nexaticket.realtime.application.UpdateCoalescer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Điểm nối WebSocket: {@code /ws/sessions/{eventSessionId}}.
 *
 * <p>Xác thực bằng token trong query param hoặc {@code Sec-WebSocket-Protocol} — trình duyệt không
 * gửi được header tuỳ ý khi mở WebSocket. Sơ đồ chỗ là dữ liệu công khai nên kết nối ẩn danh vẫn
 * được chấp nhận; token chỉ dùng khi cần biết người xem là ai.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final SeatMapWebSocketHandler handler;
    private final String allowedOrigins;

    public WebSocketConfig(
            SeatMapWebSocketHandler handler,
            @Value("${nexaticket.realtime.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public UpdateCoalescer updateCoalescer() {
        return new UpdateCoalescer();
    }

    @Bean
    public SessionRegistry<WebSocketSession> sessionRegistry(
            @Value("${nexaticket.realtime.max-sessions-per-connection:5}") int maxSessions) {
        return new SessionRegistry<>(maxSessions);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/sessions/*").setAllowedOrigins(allowedOrigins.split(","));
    }
}
