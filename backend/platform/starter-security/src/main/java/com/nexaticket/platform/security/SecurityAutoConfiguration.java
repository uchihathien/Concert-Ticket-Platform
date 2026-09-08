// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import com.nexaticket.platform.security.tenant.HttpMembershipLookup;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import com.nexaticket.platform.security.tenant.TenantFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

/**
 * PHẢI khai <b>before</b> auto-configuration bảo mật của Spring Boot.
 *
 * <p>Cả hai bên đều khai {@code SecurityFilterChain} với {@code @ConditionalOnMissingBean}, nên bên
 * nào được đánh giá trước thì bên đó thắng — và nếu không nói rõ, thứ tự đó là ngẫu nhiên theo môi
 * trường. Khi chuỗi mặc định của Boot thắng, {@code TenantFilter} không nằm trong chuỗi nào cả:
 * JWT vẫn được xác thực, request vẫn tới controller, nhưng {@code TenantContext} luôn rỗng và
 * <b>mọi</b> endpoint cần đăng nhập đều trả 401.
 *
 * <p>Lỗi này từng xanh ở test mà đỏ ở runtime, vì {@code @AutoConfigureMockMvc} làm đổi thứ tự
 * đánh giá. Khai tường minh để nó không còn phụ thuộc vào may rủi.
 */
@AutoConfiguration(before = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class)
// CHỈ áp cho ứng dụng servlet.
//
// Toàn bộ lớp này là servlet: HttpSecurity, OncePerRequestFilter, SecurityFilterChain. api-gateway
// là WebFlux, và nếu auto-config này được nạp ở đó thì hạ tầng bảo mật servlet và reactive cùng
// đăng ký một bean tên conversionServicePostProcessor — app không khởi động nổi. Gateway có
// GatewaySecurityConfig riêng theo kiểu reactive.
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(IdentityServiceProperties.class)
public class SecurityAutoConfiguration {

    /**
     * Cài đặt mặc định cho mọi service trừ identity.
     *
     * <p>identity-service khai bean {@link MembershipLookup} của riêng nó (đọc thẳng database), nên
     * {@link ConditionalOnMissingBean} khiến bean này tự nhường chỗ ở đó.
     */
    @Bean
    @ConditionalOnMissingBean(MembershipLookup.class)
    @ConditionalOnProperty(prefix = "nexaticket.identity", name = "base-url")
    public MembershipLookup httpMembershipLookup(IdentityServiceProperties properties) {
        return new HttpMembershipLookup(
                RestClient.builder().baseUrl(properties.baseUrl()).build());
    }

    @Bean
    @ConditionalOnMissingBean(TenantFilter.class)
    public TenantFilter tenantFilter(ObjectProvider<MembershipLookup> membershipLookup) {
        // Không có MembershipLookup thì filter vẫn tồn tại nhưng để scope rỗng: request đi tiếp và
        // bị chặn ở tầng uỷ quyền với 401/403. Cách này tốt hơn là service không khởi động nổi.
        return new TenantFilter(membershipLookup.getIfAvailable(() -> idpSubject -> null));
    }

    /**
     * Chuỗi filter mặc định.
     *
     * <p>Service tự khai {@link SecurityFilterChain} riêng thì bean này nhường chỗ.
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain filterChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http.csrf(csrf -> csrf.disable()) // API không dùng cookie session; token ở Authorization header
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/events/**")
                        .permitAll() // catalog công khai — @PublicEndpoint
                        .requestMatchers("/api/billing/bank/webhook/**")
                        .permitAll() // webhook SePay tự xác thực bằng cơ chế riêng
                        .requestMatchers("/internal/**")
                        .permitAll() // chỉ lộ trong mạng nội bộ, gateway không route ra ngoài
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                // PHẢI đặt sau BearerTokenAuthenticationFilter, không phải sau
                // UsernamePasswordAuthenticationFilter.
                //
                // Trong thứ tự filter của Spring Security, BearerTokenAuthenticationFilter nằm SAU
                // UsernamePasswordAuthenticationFilter. Đặt nhầm mốc thì TenantFilter chạy khi JWT
                // chưa được đưa vào SecurityContext: nó luôn thấy authentication == null, luôn dựng
                // TenantScope rỗng, và MỌI request đã đăng nhập đều nhận 401 UNAUTHENTICATED —
                // kể cả khi token hoàn toàn hợp lệ và người dùng có đủ quyền.
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
