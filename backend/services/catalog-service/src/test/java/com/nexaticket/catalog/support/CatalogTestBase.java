// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.support;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import com.nexaticket.platform.test.PostgresSingleton;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test: PostgreSQL thật, chuỗi filter HTTP thật.
 *
 * <p>Giả đúng hai thứ ở ngoài biên hệ thống — {@link JwtDecoder} (thay cho Keycloak) và
 * {@link MembershipLookup} (thay cho identity-service). Mọi thứ còn lại là thật: {@code
 * TenantFilter} thật, thứ tự filter thật, ràng buộc database thật. Đó là ranh giới đúng: chạy được
 * mà không cần dựng cả hệ thống, nhưng vẫn bắt được lớp lỗi mà test gọi thẳng handler bỏ sót.
 *
 * <p>Vòng đời container do {@link PostgresSingleton} giữ, <b>không</b> do JUnit — {@code @Container}
 * cộng với cache context của Spring làm mọi lớp test sau lớp đầu tiên đỏ.
 *
 * <p>Cố ý KHÔNG khai {@code nexaticket.identity.base-url} ở profile test: có khoá đó thì
 * {@code SecurityAutoConfiguration} tạo {@code HttpMembershipLookup} và bean giả bên dưới không
 * được dùng — test sẽ gọi ra một identity-service không tồn tại.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CatalogTestBase.StubIdentity.class)
public abstract class CatalogTestBase {

    protected static final String BEARER = "Bearer test-token";

    /** Tổ chức mà người dùng trong test là EVENT_MANAGER. */
    protected static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");

    /** Tổ chức mà người dùng KHÔNG thuộc về — dùng để kiểm cách ly giữa các tổ chức. */
    protected static final UUID OTHER_ORG = UUID.fromString("22222222-2222-4222-a222-222222222222");

    protected static final UUID USER = UUID.fromString("33333333-3333-4333-a333-333333333333");

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("catalog_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @TestConfiguration
    static class StubIdentity {

        @Bean
        @Primary
        JwtDecoder stubDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject("catalog-test-subject")
                    .claim("email", "organizer@example.com")
                    .claim("name", "Ban Tổ Chức")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();
        }

        @Bean
        MembershipLookup stubMemberships() {
            return claims -> new MembershipLookup.Principal(
                    UserId.of(USER), Map.of(TenantId.of(ORG), Role.EVENT_MANAGER), false);
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Bàn sạch trước mỗi ca.
     *
     * <p>Container dùng lại giữa các lần chạy (PostgresSingleton không tắt nó), nên dữ liệu của
     * lần chạy trước còn nguyên. Không dọn thì mọi khẳng định về số lượng đều xanh ở lần đầu và
     * đỏ từ lần thứ hai — kiểu đỏ khiến người ta nới lỏng khẳng định thay vì sửa nguyên nhân.
     */
    @BeforeEach
    void resetCatalog() {
        jdbc.update("TRUNCATE ticket_types, event_sessions, events, venue_zones, venues, outbox CASCADE");
    }
}
