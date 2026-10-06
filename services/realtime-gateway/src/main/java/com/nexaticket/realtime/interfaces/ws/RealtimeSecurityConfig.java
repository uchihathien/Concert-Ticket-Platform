// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.interfaces.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chuỗi bảo mật của realtime-gateway.
 *
 * <p>Service này <b>không dùng</b> {@code com.nexaticket:starter-security} như mười service kia, và
 * đó là có lý do: nó không có khái niệm tổ chức nào để lọc, nên {@code TenantFilter} — vốn gọi sang
 * identity-service cho mỗi request — chỉ thêm một phụ thuộc mà không giữ thêm bất biến nào.
 *
 * <p>Nhưng khi <b>không</b> khai chuỗi nào cả thì Spring Boot áp mặc định "mọi request phải xác
 * thực", và đó là thứ đã lặng lẽ hỏng hai đường:
 *
 * <ul>
 *   <li><b>{@code /actuator/health} trả 401.</b> {@code dev-up.sh} chờ health bằng {@code curl -fs}
 *       nên luôn kết luận "realtime-gateway KHÔNG lên sau 180s" dù nó chạy hoàn hảo. Ở production
 *       thì nặng hơn: liveness probe của Kubernetes gọi đúng đường này, nhận 401, và giết một pod
 *       khoẻ mạnh — lặp lại mãi.
 *   <li><b>WebSocket handshake trả 401.</b> {@link WebSocketConfig} ghi rõ "sơ đồ chỗ là dữ liệu
 *       công khai nên kết nối ẩn danh vẫn được chấp nhận", nhưng chuỗi mặc định chặn trước khi
 *       handler kịp chạy. Ý định trong tài liệu và hành vi thực tế đã ngược nhau.
 * </ul>
 *
 * <p>Không có session HTTP: WebSocket giữ trạng thái trong {@code SessionRegistry} của chính nó, và
 * một session servlet cho mỗi kết nối chỉ là bộ nhớ bị giữ vô ích.
 */
@Configuration
public class RealtimeSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Sơ đồ chỗ là dữ liệu công khai — đúng những gì `GET /v1/sessions/{id}/seats`
                        // đã trả cho khách vãng lai. Token (nếu có) đi trong query param hoặc
                        // Sec-WebSocket-Protocol và được handler đọc, vì trình duyệt không gửi được
                        // header tuỳ ý khi mở WebSocket.
                        .requestMatchers("/ws/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
