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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnWebApplication
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
                .addFilterAfter(tenantFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
