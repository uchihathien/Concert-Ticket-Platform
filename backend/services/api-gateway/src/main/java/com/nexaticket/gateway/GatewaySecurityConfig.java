// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway chỉ xác minh token hợp lệ.
 *
 * <p>Việc "user này có phải thành viên org này không" do từng service tự kiểm tra. Gateway không
 * được là điểm tin cậy duy nhất (services.md §0) — nếu ai đó gọi thẳng vào service trong mạng nội
 * bộ, service vẫn phải tự bảo vệ.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                // PHẢI bật ở đây, không phải bằng `globalcors` trong YAML: CORS xử lý bên trong
                // chuỗi bảo mật thì nằm TRƯỚC bước uỷ quyền, nên preflight OPTIONS — vốn không
                // mang header Authorization — được trả lời thay vì nhận 401. Xem CorsConfig.
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange.pathMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/v1/events/**", "/v1/sessions/*/seats")
                        .permitAll()
                        .pathMatchers("/api/billing/bank/webhook/**")
                        .permitAll()
                        // /internal/** không bao giờ được route ra ngoài — xem RouteConfig.
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
