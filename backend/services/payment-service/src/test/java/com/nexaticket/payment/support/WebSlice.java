// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.support;

import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Nền cho test lát cắt web ({@code @WebMvcTest}) của payment.
 *
 * <p>Chuỗi bảo mật là <b>thật</b>: {@code SecurityAutoConfiguration} của starter-security được nạp tường
 * minh (slice không tự nạp auto-config của platform), nên {@code InternalApiFilter}, {@code TenantFilter},
 * danh sách {@code permitAll} và {@code GlobalExceptionHandler} đều là code chạy ở production. Chỉ hai
 * thứ ở ngoài biên được giả: {@link JwtDecoder} (thay Keycloak) và {@link MembershipLookup} (thay
 * identity-service).
 */
@TestConfiguration
public class WebSlice {

    /** Bí mật nội bộ của test. Khoá giả, không phải giá trị thật của môi trường nào. */
    public static final String INTERNAL_SECRET = "test-internal-secret";

    /** Token mà decoder giả chấp nhận. Mọi chuỗi khác là token hỏng. */
    public static final String VALID_TOKEN = "valid-token";

    @Bean
    @Primary
    JwtDecoder stubJwtDecoder() {
        return token -> {
            if (!VALID_TOKEN.equals(token)) {
                throw new JwtException("token hỏng");
            }
            Instant now = Instant.parse("2026-09-09T03:00:00Z");
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject("payment-web-test")
                    .claim("email", "khach@example.com")
                    .issuedAt(now)
                    .expiresAt(now.plus(3650, ChronoUnit.DAYS))
                    .build();
        };
    }

    @Bean
    MembershipLookup stubMemberships() {
        return claims -> new MembershipLookup.Principal(
                UserId.of(UUID.fromString("33333333-3333-4333-a333-333333333333")), Map.of(), false);
    }
}
