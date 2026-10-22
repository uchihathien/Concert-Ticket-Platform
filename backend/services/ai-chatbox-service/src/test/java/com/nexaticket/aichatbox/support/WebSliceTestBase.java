// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Nền cho test lát cắt web: <b>chuỗi bảo mật thật</b> của starter-security, chỉ thay hai thứ nằm
 * ngoài tiến trình.
 *
 * <ul>
 *   <li>{@link JwtDecoder} — không có Keycloak; token là một chuỗi tuỳ ý, decoder giả trả về
 *       {@link Jwt} đã dựng sẵn. {@code BearerTokenAuthenticationFilter} vẫn chạy thật.
 *   <li>{@link MembershipLookup} — không có identity-service; {@code TenantFilter} vẫn chạy thật và
 *       vẫn quyết định 404 / tổ chức đang thao tác theo đúng luật của nó.
 * </ul>
 *
 * <p>Không dùng {@code spring-security-test}: {@code jwt()} của nó đặt thẳng Authentication vào
 * context và đi vòng qua bộ lọc bearer — tức là bỏ qua đúng phần cần kiểm.
 */
@WebSliceTestBase.WithPlatformSecurity
public abstract class WebSliceTestBase {

    /** Bí mật dùng chung cho {@code /internal/**} trong các test bật {@code InternalApiFilter}. */
    public static final String INTERNAL_SECRET = "test-internal-secret";

    /** Kéo auto-config bảo mật của nền tảng vào lát cắt — {@code @WebMvcTest} không tự nạp nó. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @java.lang.annotation.Inherited
    @ImportAutoConfiguration(com.nexaticket.platform.security.SecurityAutoConfiguration.class)
    public @interface WithPlatformSecurity {}

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean
    protected MembershipLookup membershipLookup;

    @BeforeEach
    void rejectUnknownTokens() {
        // Bản test lồng nhau (@Nested) không được Spring reset mock giữa các ca: tự reset để mỗi ca
        // bắt đầu từ bàn sạch và không thừa hưởng stub của ca trước.
        reset(jwtDecoder, membershipLookup);
        // doThrow().when() thay vì when().thenThrow(): không gọi lại stub cũ nếu mock chưa được reset.
        doThrow(new BadJwtException("unknown token")).when(jwtDecoder).decode(anyString());
    }

    /** Token hợp lệ của một người dùng có các membership đã cho. */
    protected String tokenFor(UUID userId, Map<TenantId, Role> memberships, boolean superAdmin) {
        String token = "token-" + userId;
        String subject = "sub-" + userId;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject)
                .claim("email", userId + "@example.test")
                .issuedAt(Instant.parse("2026-10-22T09:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        doReturn(jwt).when(jwtDecoder).decode(token);
        when(membershipLookup.resolve(argThat(claims -> claims != null && subject.equals(claims.subject()))))
                .thenReturn(new MembershipLookup.Principal(UserId.of(userId), memberships, superAdmin));
        return token;
    }

    protected String customerToken(UUID userId) {
        return tokenFor(userId, Map.of(), false);
    }

    /** JWT hợp lệ nhưng identity-service không trả lời được — TenantScope rỗng. */
    protected String tokenWithoutIdentity(UUID userId) {
        String token = "orphan-" + userId;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("orphan-sub-" + userId)
                .issuedAt(Instant.parse("2026-10-22T09:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        doReturn(jwt).when(jwtDecoder).decode(token);
        return token;
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
