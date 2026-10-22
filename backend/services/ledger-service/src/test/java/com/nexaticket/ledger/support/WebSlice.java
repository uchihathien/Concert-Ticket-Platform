// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.support;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Nền cho test lát cắt web của ledger: chuỗi bảo mật thật của starter-security, chỉ giả {@link JwtDecoder}
 * và {@link MembershipLookup}.
 *
 * <p>Người gọi được chọn theo từng ca bằng {@link #actAs}; giá trị đọc lại ở mỗi request, nên một context
 * Spring dùng chung được cho mọi ca mà không ca nào thừa hưởng quyền của ca trước (xem {@code reset}).
 */
@TestConfiguration
public class WebSlice {

    public static final String VALID_TOKEN = "valid-token";

    /** Tổ chức mà người dùng thường là ORG_OWNER — để chứng minh quyền tổ chức không mở được sổ cái. */
    public static final UUID OWN_ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");

    public enum Caller {
        SUPER_ADMIN,
        ORG_OWNER,
        /** Token hợp lệ nhưng identity-service không biết người này. */
        UNKNOWN
    }

    private static final AtomicReference<Caller> CALLER = new AtomicReference<>(Caller.ORG_OWNER);

    public static void actAs(Caller caller) {
        CALLER.set(caller);
    }

    public static void reset() {
        CALLER.set(Caller.ORG_OWNER);
    }

    @Bean
    @Primary
    JwtDecoder stubJwtDecoder() {
        return token -> {
            if (!VALID_TOKEN.equals(token)) {
                throw new BadJwtException("token hỏng");
            }
            Instant now = Instant.parse("2026-09-09T03:00:00Z");
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject("ledger-web-test")
                    .claim("email", "nguoi-dung@example.com")
                    .issuedAt(now)
                    .expiresAt(now.plus(3650, ChronoUnit.DAYS))
                    .build();
        };
    }

    @Bean
    MembershipLookup stubMemberships() {
        UserId user = UserId.of(UUID.fromString("33333333-3333-4333-a333-333333333333"));
        return claims -> switch (CALLER.get()) {
            case SUPER_ADMIN -> new MembershipLookup.Principal(user, Map.of(), true);
            case ORG_OWNER -> new MembershipLookup.Principal(user, Map.of(TenantId.of(OWN_ORG), Role.ORG_OWNER), false);
            case UNKNOWN -> null;
        };
    }
}
