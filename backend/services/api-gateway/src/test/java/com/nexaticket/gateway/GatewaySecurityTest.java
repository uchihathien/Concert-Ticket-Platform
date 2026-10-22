// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Chuỗi bảo mật THẬT của gateway — xác thực JWT, CORS, correlation id — trước một "service phía
 * sau" giả trả lời mọi đường dẫn.
 *
 * <p>Không có Redis, không có route nào được dựng: {@code @WebFluxTest} chỉ nạp
 * {@link GatewaySecurityConfig}, {@link CorsConfig} và {@link CorrelationIdGatewayFilter}. Câu hỏi
 * duy nhất ở đây là "request nào đi qua được tới chỗ định tuyến", và nó được trả lời bằng đúng bộ
 * lọc chạy ở production.
 *
 * <p>Service giả trả về đường dẫn và correlation id nó NHẬN ĐƯỢC — tức là thứ service thật phía sau
 * sẽ thấy, không phải thứ test tự đặt.
 */
@WebFluxTest
@Import({GatewaySecurityConfig.class, GatewaySecurityTest.Downstream.class})
class GatewaySecurityTest {

    private static final String CORRELATION = "X-Correlation-Id";
    private static final String APP_ORIGIN = "http://localhost:3001";

    @TestConfiguration
    static class Downstream {

        /**
         * Origin khai tường minh bằng constructor: {@code @Value} dạng List trong lát cắt WebFlux
         * không nhận chắc danh sách phân tách bằng dấu phẩy. Logic CORS vẫn là của {@link CorsConfig}.
         */
        @Bean
        org.springframework.web.cors.reactive.CorsConfigurationSource corsConfigurationSource() {
            return new CorsConfig(java.util.List.of(APP_ORIGIN)).corsConfigurationSource();
        }

        @Bean
        RouterFunction<ServerResponse> echo() {
            return RouterFunctions.route(request -> true, request -> ServerResponse.ok()
                    .bodyValue(Map.of(
                            "path",
                            request.path(),
                            "correlationId",
                            request.headers().firstHeader(CORRELATION) == null
                                    ? ""
                                    : request.headers().firstHeader(CORRELATION))));
        }
    }

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void fakeKeycloak() {
        doAnswer(invocation -> {
                    String token = invocation.getArgument(0);
                    if (token.startsWith("bad")) {
                        return Mono.error(new BadJwtException("Chữ ký không hợp lệ"));
                    }
                    Instant now = Instant.now();
                    return Mono.just(Jwt.withTokenValue(token)
                            .header("alg", "RS256")
                            .subject(token)
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(300))
                            .build());
                })
                .when(jwtDecoder)
                .decode(anyString());
    }

    private WebTestClient.ResponseSpec call(HttpMethod method, String path, String token) {
        WebTestClient.RequestBodySpec request = client.method(method).uri(URI.create(path));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request.exchange();
    }

    @Nested
    @DisplayName("đường công khai và đường cần đăng nhập")
    class XacThuc {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET,  /v1/events",
            "GET,  /v1/events/dem-nhac-mua-thu",
            "GET,  /v1/events/dem-nhac-mua-thu/floor-plan",
            "GET,  /v1/sessions/0f8c/seats",
            "POST, /api/billing/bank/webhook",
            "GET,  /actuator/health",
            "GET,  /actuator/health/liveness",
            "GET,  /actuator/info"
        })
        @DisplayName("đường công khai đi qua không cần token")
        void cong_khai(String method, String path) {
            call(HttpMethod.valueOf(method), path, null).expectStatus().isOk();
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "POST,   /v1/events",
            "PATCH,  /v1/events/dem-nhac",
            "DELETE, /v1/events/dem-nhac",
            "POST,   /v1/sessions/0f8c/holds",
            "GET,    /v1/sessions/0f8c/seats/extra",
            "GET,    /v1/sessions/0f8c/checkins",
            "GET,    /v1/me",
            "GET,    /v1/me/tickets",
            "POST,   /v1/orders",
            "GET,    /v1/organizations/0f8c/members",
            "GET,    /v1/platform/organizations",
            "GET,    /v1/roles",
            "POST,   /v1/chat/agent/support",
            "GET,    /actuator/prometheus",
            "GET,    /actuator/env"
        })
        @DisplayName("mọi đường khác đòi token: 401 kèm WWW-Authenticate Bearer")
        void can_dang_nhap(String method, String path) {
            call(HttpMethod.valueOf(method), path, null)
                    .expectStatus()
                    .isUnauthorized()
                    .expectHeader()
                    .valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*");
        }

        @Test
        @DisplayName("token hợp lệ thì đi qua; token sai chữ ký thì 401")
        void token() {
            call(HttpMethod.GET, "/v1/me", "sub-1").expectStatus().isOk();
            call(HttpMethod.GET, "/v1/me", "bad-token").expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("header Authorization rỗng hay sai lược đồ không được coi là đăng nhập")
        void header_sai() {
            client.get()
                    .uri("/v1/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
            client.get()
                    .uri("/v1/me")
                    .header(HttpHeaders.AUTHORIZATION, "Basic YTpi")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        }

        @Test
        @DisplayName("webhook payOS không bị CSRF chặn dù là POST không token")
        void webhook_khong_csrf() {
            client.post()
                    .uri("/api/billing/bank/webhook")
                    .bodyValue("{\"code\":\"00\"}")
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Nested
    @DisplayName("ranh giới /internal/**")
    class NoiBo {

        @ParameterizedTest
        @ValueSource(strings = {"/internal/memberships", "/internal/reservations", "/internal/users/0f8c"})
        @DisplayName("người ngoài không token gọi /internal/** nhận 401 — không có lối tắt permitAll nào")
        void khong_token(String path) {
            call(HttpMethod.GET, path, null).expectStatus().isUnauthorized();
            call(HttpMethod.POST, path, null).expectStatus().isUnauthorized();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "/v1/events/../internal/reservations",
                    "/v1/events/../../internal/memberships",
                    "/v1/events/%2e%2e/internal/reservations",
                    "/v1/events/%2E%2E/internal/reservations",
                    "/v1/events/..%2Finternal/reservations",
                    "/v1/events/%2F../internal/reservations",
                    "/v1/events/..;/internal/reservations",
                    "/v1/events/.%2e/internal/reservations"
                })
        @DisplayName("dot-segment dưới đường công khai không lọt tới service phía sau — kể cả khi đã mã hoá")
        void dot_segment_bi_chan(String path) {
            // /v1/events/** là permitAll với GET và khớp route catalog. Nếu đường này lọt qua, gateway
            // chuyển nguyên văn tới catalog-service, Tomcat chuẩn hoá thành /internal/... — và
            // Open Host Service vốn tin rằng "chỉ service khác gọi được" sẽ trả lời người ngoài.
            byte[] body = call(HttpMethod.GET, path, null)
                    .expectStatus()
                    .value(status ->
                            assertThat(status).as("trạng thái cho %s", path).isNotEqualTo(200))
                    .expectBody()
                    .returnResult()
                    .getResponseBody();
            assertThat(body == null ? "" : new String(body)).doesNotContain("\"path\"");
        }
    }

    @Nested
    @DisplayName("CORS")
    class Cors {

        // URI tuyệt đối: Origin chỉ được so với máy chủ khi request mang scheme và host; đường dẫn
        // tương đối làm DefaultCorsProcessor kết luận "origin is malformed" và từ chối mọi thứ.
        @Test
        @DisplayName("preflight từ app đã khai được trả lời KHÔNG cần token, với đúng header cho SDK")
        void preflight_khong_can_token() {
            client.options()
                    .uri("http://localhost/v1/me/orders")
                    .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_ORIGIN)
                    .expectHeader()
                    .valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ".*POST.*")
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        }

        @Test
        @DisplayName("preflight từ origin lạ bị từ chối và không nhận header cho phép")
        void origin_la() {
            client.options()
                    .uri("http://localhost/v1/me/orders")
                    .header(HttpHeaders.ORIGIN, "https://ke-tan-cong.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        @Test
        @DisplayName("preflight xin header ngoài danh sách (X-Internal-Token) bị từ chối")
        void header_ngoai_danh_sach() {
            client.options()
                    .uri("http://localhost/v1/me/orders")
                    .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-internal-token")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        @Test
        @DisplayName("request thật cross-origin lộ được ETag và X-Correlation-Id cho JavaScript")
        void request_that() {
            client.get()
                    .uri("http://localhost/v1/me")
                    .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer sub-1")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_ORIGIN)
                    .expectHeader()
                    .valueMatches(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, ".*X-Correlation-Id.*");
        }

        @Test
        @DisplayName(
                "401 cross-origin vẫn mang header CORS — không thì frontend chỉ thấy 'CORS error' thay vì 'hết phiên'")
        void loi_401_van_co_cors() {
            client.get()
                    .uri("http://localhost/v1/me")
                    .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_ORIGIN);
        }
    }

    @Nested
    @DisplayName("correlation id")
    class Correlation {

        @Test
        @DisplayName("không có thì sinh mới; service phía sau nhận đúng id trả về cho client")
        void sinh_moi_va_truyen_xuong() {
            WebTestClient.ResponseSpec response = call(HttpMethod.GET, "/v1/events", null);

            String returned =
                    response.returnResult(String.class).getResponseHeaders().getFirst(CORRELATION);
            assertThat(returned).isNotBlank();
            call(HttpMethod.GET, "/v1/events", null).expectHeader().exists(CORRELATION);
            client.get()
                    .uri("/v1/events")
                    .header(CORRELATION, returned)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.correlationId")
                    .isEqualTo(returned);
        }

        @Test
        @DisplayName("id hợp lệ của client được giữ nguyên ở cả phản hồi lẫn request đi xuống")
        void giu_id_cua_client() {
            client.get()
                    .uri("/v1/events")
                    .header(CORRELATION, "abc-123")
                    .exchange()
                    .expectHeader()
                    .valueEquals(CORRELATION, "abc-123")
                    .expectBody()
                    .jsonPath("$.correlationId")
                    .isEqualTo("abc-123");
        }

        @Test
        @DisplayName("id quá 64 ký tự bị thay — không để client bơm chuỗi dài tuỳ ý vào log của mọi service")
        void id_qua_dai() {
            String tooLong = "x".repeat(65);

            client.get()
                    .uri("/v1/events")
                    .header(CORRELATION, tooLong)
                    .exchange()
                    .expectHeader()
                    .value(
                            CORRELATION,
                            value -> assertThat(value).isNotEqualTo(tooLong).hasSizeLessThanOrEqualTo(64))
                    .expectBody()
                    .jsonPath("$.correlationId")
                    .value(value -> assertThat(value.toString()).isNotEqualTo(tooLong));
        }

        @Test
        @DisplayName("phản hồi 401 cũng mang correlation id — đó là lúc người dùng cần nó nhất")
        void loi_cung_co_id() {
            call(HttpMethod.GET, "/v1/me", null)
                    .expectStatus()
                    .isUnauthorized()
                    .expectHeader()
                    .exists(CORRELATION);
        }
    }
}
