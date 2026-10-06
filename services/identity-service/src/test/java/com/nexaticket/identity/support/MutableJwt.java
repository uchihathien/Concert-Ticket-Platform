// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Keycloak giả, điều khiển được từ trong ca test.
 *
 * <p>Bộ test thu hồi phiên cần dựng được những token khác nhau về {@code iat} và {@code sid} — đó
 * chính là hai thứ mà cơ chế thu hồi dựa vào. Một {@link JwtDecoder} trả hằng số không diễn đạt
 * được "token cũ" với "token vừa phát", nên không kiểm được điều quan trọng nhất.
 *
 * <p>Trạng thái là biến tĩnh chứ không phải trường của bean: context của Spring được cache giữa các
 * lớp test, nên bean này sống lâu hơn bất kỳ ca test nào. {@link #reset()} phải được gọi ở
 * {@code @BeforeEach} — không có nó thì một ca thừa hưởng danh tính của ca trước, và kiểu hỏng đó
 * chỉ lộ ra khi đổi thứ tự chạy.
 */
@TestConfiguration
public class MutableJwt {

    public static final String DEFAULT_SUBJECT = "keycloak-sub-access-test";
    public static final String DEFAULT_EMAIL = "access-test@example.com";

    private static volatile String subject = DEFAULT_SUBJECT;
    private static volatile String email = DEFAULT_EMAIL;
    private static volatile String fullName = "Nguoi Dung Thu";
    private static volatile String sessionId = "device-1";
    private static volatile Instant issuedAt = Instant.now();

    /** Về mặc định. Gọi ở {@code @BeforeEach} của mọi lớp test dùng bean này. */
    public static void reset() {
        subject = DEFAULT_SUBJECT;
        email = DEFAULT_EMAIL;
        fullName = "Nguoi Dung Thu";
        sessionId = "device-1";
        issuedAt = Instant.now();
    }

    /** Đổi danh tính — dùng để đóng vai người thứ hai trong cùng một ca. */
    public static void actAs(String subject, String email, String fullName) {
        MutableJwt.subject = subject;
        MutableJwt.email = email;
        MutableJwt.fullName = fullName;
    }

    /** Giả lập "token phát từ lúc nào" — thứ mà cơ chế thu hồi toàn bộ phiên so sánh với. */
    public static void issuedAt(Instant instant) {
        issuedAt = instant;
    }

    /** Giả lập thiết bị: mỗi {@code sid} là một phiên SSO khác nhau. */
    public static void onDevice(String sid) {
        sessionId = sid;
    }

    @Bean
    @Primary
    JwtDecoder stubDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", email)
                .claim("name", fullName)
                .claim("sid", sessionId)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(1, ChronoUnit.HOURS))
                .build();
    }
}
