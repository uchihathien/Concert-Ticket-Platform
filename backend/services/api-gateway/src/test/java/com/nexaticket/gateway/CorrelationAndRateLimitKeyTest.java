// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Hai bộ phận nhỏ ở biên, kiểm không cần context: bộ lọc correlation id và những khe hở của khoá
 * rate limit mà {@code RateLimitKeyResolverTest} chưa chạm tới.
 */
class CorrelationAndRateLimitKeyTest {

    private static final String HEADER = "X-Correlation-Id";

    @Nested
    @DisplayName("bộ lọc correlation id")
    class Correlation {

        private final CorrelationIdGatewayFilter filter = new CorrelationIdGatewayFilter();

        /** Chạy bộ lọc và trả về request mà mắt xích tiếp theo nhận được. */
        private ServerWebExchange run(MockServerWebExchange exchange) {
            AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
            WebFilterChain chain = next -> {
                downstream.set(next);
                return Mono.empty();
            };
            filter.filter(exchange, chain).block();
            return downstream.get();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("id rỗng hay chỉ khoảng trắng bị thay bằng id mới")
        void id_rong(String incoming) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/v1/events").header(HEADER, incoming));

            ServerWebExchange next = run(exchange);

            String generated = exchange.getResponse().getHeaders().getFirst(HEADER);
            assertThat(generated).isNotBlank().isNotEqualTo(incoming);
            assertThat(next.getRequest().getHeaders().get(HEADER)).containsExactly(generated);
        }

        @Test
        @DisplayName("đúng 64 ký tự được giữ, 65 ký tự bị thay")
        void bien_do_dai() {
            String max = "a".repeat(64);
            MockServerWebExchange kept =
                    MockServerWebExchange.from(MockServerHttpRequest.get("/").header(HEADER, max));
            assertThat(run(kept).getRequest().getHeaders().getFirst(HEADER)).isEqualTo(max);

            MockServerWebExchange replaced =
                    MockServerWebExchange.from(MockServerHttpRequest.get("/").header(HEADER, max + "a"));
            assertThat(run(replaced).getRequest().getHeaders().getFirst(HEADER)).hasSizeLessThanOrEqualTo(64);
        }

        @Test
        @DisplayName(
                "client gửi HAI header thì service phía sau chỉ thấy một giá trị — không có hai id cho một request")
        void hai_header() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/").header(HEADER, "id-mot").header(HEADER, "id-hai"));

            ServerWebExchange next = run(exchange);

            assertThat(next.getRequest().getHeaders().get(HEADER)).hasSize(1);
            assertThat(exchange.getResponse().getHeaders().get(HEADER)).hasSize(1);
        }

        @Test
        @DisplayName("chạy trước mọi bộ lọc khác, kể cả chuỗi bảo mật — 401 cũng có id")
        void thu_tu() {
            assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    @Nested
    @DisplayName("khoá rate limit — những khe hở còn lại")
    class KhoaRateLimit {

        private String keyOf(ServerWebExchange exchange) {
            RateLimitConfig config = new RateLimitConfig();
            ReflectionTestUtils.setField(config, "trustedProxyCount", 1);
            return config.principalOrIpKeyResolver().resolve(exchange).block();
        }

        @Test
        @DisplayName("không có X-Forwarded-For thì đếm theo địa chỉ kết nối")
        void khong_co_x_forwarded_for() {
            ServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/v1/events").remoteAddress(new InetSocketAddress("198.51.100.7", 1234)));

            assertThat(keyOf(exchange)).isEqualTo("ip:198.51.100.7");
        }

        @Test
        @DisplayName("không biết địa chỉ nào thì dùng một khoá chung thay vì ném lỗi làm hỏng request")
        void khong_co_dia_chi() {
            assertThat(keyOf(MockServerWebExchange.from(MockServerHttpRequest.get("/v1/events"))))
                    .isEqualTo("ip:unknown");
        }

        @Test
        @DisplayName("hai cách viết của cùng một địa chỉ IPv6 là cùng một khoá")
        void ipv6_chuan_hoa() {
            String short1 = keyOf(MockServerWebExchange.from(
                    MockServerHttpRequest.get("/").header("X-Forwarded-For", "2001:db8::1")));
            String long1 = keyOf(MockServerWebExchange.from(
                    MockServerHttpRequest.get("/").header("X-Forwarded-For", "2001:0db8:0:0:0:0:0:1")));

            assertThat(short1).isEqualTo(long1).startsWith("ip:");
        }

        @Test
        @DisplayName("người đăng nhập bằng JWT được đếm theo sub — đúng danh tính mà Keycloak bảo đảm")
        void jwt_dem_theo_sub() {
            Jwt jwt = Jwt.withTokenValue("t")
                    .header("alg", "RS256")
                    .subject("kc-sub-123")
                    .claim("preferred_username", "ten-tu-dat")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
            ServerWebExchange exchange =
                    new ServerWebExchangeDecorator(MockServerWebExchange.from(MockServerHttpRequest.get("/"))) {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <T extends Principal> Mono<T> getPrincipal() {
                            return (Mono<T>) Mono.just(new JwtAuthenticationToken(jwt));
                        }
                    };

            assertThat(keyOf(exchange)).isEqualTo("user:kc-sub-123");
        }

        @Test
        @DisplayName("khoá người dùng và khoá IP không bao giờ trùng nhau, dù sub trông giống một IP")
        void khong_trung_khong_gian_khoa() {
            ServerWebExchange anonymous = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress("203.0.113.5", 1)));
            ServerWebExchange user =
                    new ServerWebExchangeDecorator(MockServerWebExchange.from(MockServerHttpRequest.get("/"))) {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <T extends Principal> Mono<T> getPrincipal() {
                            return (Mono<T>) Mono.just((Principal) () -> "203.0.113.5");
                        }
                    };

            assertThat(keyOf(user)).isNotEqualTo(keyOf(anonymous));
        }
    }
}
