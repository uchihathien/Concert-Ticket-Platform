// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.yaml.snakeyaml.Yaml;

/**
 * Bảng route trong {@code application.yml}, kiểm KHÔNG cần Spring context, Redis hay service phía
 * sau.
 *
 * <p>{@code GatewayRoutingIT} kiểm cùng bảng này qua {@code RouteLocator} thật nhưng cần Redis để
 * context lên được — tức là không chạy được ở máy không có Docker, và nó chỉ ghim vài cặp chồng lấn.
 * Ở đây đọc thẳng file YAML, dựng predicate bằng chính {@link PathRoutePredicateFactory} và
 * {@link MethodRoutePredicateFactory} của gateway, rồi duyệt theo đúng thứ tự khai báo — route đầu
 * tiên khớp là route thắng, như gateway làm.
 *
 * <p>Danh sách đường dẫn bên dưới chép từ {@code @RequestMapping} của từng service. Thêm endpoint
 * mới ở service nào thì thêm một dòng ở đây; thiếu route là 404 của chính gateway, và người ta sẽ đi
 * soi một service đang chạy hoàn toàn tốt.
 *
 * <p>Route thống kê của ban tổ chức khai bằng code ({@code OrganizationStatsRoutes}, chỉ ngoài
 * prod) nên không nằm trong bảng YAML và không được kiểm ở đây.
 */
class GatewayRouteTableTest {

    private record RouteDef(String id, URI uri, Predicate<ServerWebExchange> predicate, List<String> paths) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+):([^}]*)}");

    private static List<RouteDef> routes;

    @BeforeAll
    static void loadYaml() throws IOException {
        routes = new ArrayList<>();
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            for (Map<String, Object> route : routeList(root)) {
                routes.add(toRoute(route));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routeList(Map<String, Object> root) {
        Map<String, Object> node = root;
        for (String key : List.of("spring", "cloud", "gateway", "server", "webflux")) {
            node = (Map<String, Object>) node.get(key);
        }
        return (List<Map<String, Object>>) node.get("routes");
    }

    /** Giá trị mặc định của {@code ${BIẾN:mặc-định}} — đúng thứ có hiệu lực khi không đặt biến môi trường. */
    private static String resolve(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        return matcher.replaceAll(m -> Matcher.quoteReplacement(m.group(2)));
    }

    @SuppressWarnings("unchecked")
    private static RouteDef toRoute(Map<String, Object> route) {
        Predicate<ServerWebExchange> predicate = exchange -> true;
        List<String> paths = new ArrayList<>();
        for (Object raw : (List<Object>) route.get("predicates")) {
            String definition = resolve(raw.toString());
            String name = definition.substring(0, definition.indexOf('='));
            List<String> args =
                    List.of(definition.substring(definition.indexOf('=') + 1).split(","));
            switch (name) {
                case "Path" -> {
                    paths.addAll(args);
                    predicate = predicate.and(new PathRoutePredicateFactory()
                            .apply(new PathRoutePredicateFactory.Config().setPatterns(args)));
                }
                case "Method" -> {
                    MethodRoutePredicateFactory.Config config = new MethodRoutePredicateFactory.Config();
                    config.setMethods(args.stream().map(HttpMethod::valueOf).toArray(HttpMethod[]::new));
                    predicate = predicate.and(new MethodRoutePredicateFactory().apply(config));
                }
                default -> throw new IllegalStateException("Predicate chưa được mô phỏng trong test: " + name);
            }
        }
        return new RouteDef(
                route.get("id").toString(), URI.create(resolve(route.get("uri").toString())), predicate, paths);
    }

    private static Optional<RouteDef> match(HttpMethod method, String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path)));
        return routes.stream().filter(route -> route.predicate().test(exchange)).findFirst();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        // catalog-service (8091)
        "GET,    /v1/events,                                                         catalog, 8091",
        "GET,    /v1/events/trending,                                                catalog, 8091",
        "GET,    /v1/events/dem-nhac-mua-thu/floor-plan,                             catalog, 8091",
        "POST,   /v1/organizations/0f8c/venues,                                      catalog, 8091",
        "PUT,    /v1/organizations/0f8c/venues/1a/zones,                             catalog, 8091",
        "POST,   /v1/organizations/0f8c/venues/1a/floor-plan/preview,                catalog, 8091",
        "PATCH,  /v1/organizations/0f8c/events/1a/sessions/2b/ticket-types/3c,       catalog, 8091",
        "POST,   /v1/organizations/0f8c/events/from-template,                        catalog, 8091",
        "GET,    /v1/organizations/0f8c/events/1a/master-data,                       catalog, 8091",
        "GET,    /v1/organizations/0f8c/dashboard,                                   catalog, 8091",
        "GET,    /v1/organizations/0f8c/concert-templates,                           catalog, 8091",
        "GET,    /v1/organizations/0f8c/concert-templates/1a,                        catalog, 8091",
        "POST,   /v1/organizations/0f8c/uploads/poster,                              catalog, 8091",
        "GET,    /v1/organizations/0f8c/checkin-sessions,                            catalog, 8091",
        "POST,   /v1/platform/concert-templates/1a/activate,                         catalog, 8091",
        "GET,    /v1/platform/concert-templates,                                     catalog, 8091",
        // ticketing-service (8097)
        "GET,    /v1/me/tickets,                                                     ticketing, 8097",
        "GET,    /v1/orders/0f8c/tickets,                                            ticketing, 8097",
        "POST,   /v1/sessions/0f8c/checkins,                                         ticketing, 8097",
        "GET,    /v1/organizations/0f8c/tickets,                                     ticketing, 8097",
        "POST,   /v1/organizations/0f8c/sessions/1a/checkins,                        ticketing, 8097",
        // ordering-service (8093)
        "POST,   /v1/orders,                                                         ordering, 8093",
        "GET,    /v1/orders/0f8c,                                                    ordering, 8093",
        "POST,   /v1/orders/0f8c/cancel,                                             ordering, 8093",
        "GET,    /v1/me/orders,                                                      ordering, 8093",
        // inventory-service (8092)
        "POST,   /v1/sessions/0f8c/holds,                                            inventory-hold-create, 8092",
        "GET,    /v1/sessions/0f8c/seats,                                            inventory, 8092",
        "DELETE, /v1/holds/0f8c,                                                     inventory, 8092",
        // analytics (8099), ledger (8094), payment (8095)
        "GET,    /v1/admin/organizations/0f8c/sales,                                 analytics, 8099",
        "GET,    /v1/platform/trial-balance,                                         ledger, 8094",
        "GET,    /v1/platform/organizations/0f8c/balance,                            ledger, 8094",
        "POST,   /api/billing/bank/webhook,                                          payment-webhook, 8095",
        // ai-chatbox-service (8101)
        "POST,   /v1/chat/agent/support,                                             ai-chat-turn, 8101",
        "GET,    /v1/chat/agent/sessions/0f8c/messages,                              ai-chatbox, 8101",
        "POST,   /v1/chat/agent/sessions/0f8c/handoff,                               ai-chatbox, 8101",
        "POST,   /v1/support/handoffs/0f8c/resolve,                                  ai-chatbox, 8101",
        "DELETE, /v1/support/knowledge/chunks/0f8c,                                  ai-chatbox, 8101",
        // identity-service (8090) — mọi đường của mười controller
        "GET,    /v1/me,                                                             identity, 8090",
        "PATCH,  /v1/me,                                                             identity, 8090",
        "GET,    /v1/me/organizations,                                               identity, 8090",
        "GET,    /v1/me/permissions,                                                 identity, 8090",
        "GET,    /v1/me/invitations,                                                 identity, 8090",
        "POST,   /v1/me/invitations/0f8c/accept,                                     identity, 8090",
        "GET,    /v1/roles,                                                          identity, 8090",
        "POST,   /v1/invitations/tok/accept,                                         identity, 8090",
        "GET,    /v1/organizations/0f8c,                                             identity, 8090",
        "PATCH,  /v1/organizations/0f8c,                                             identity, 8090",
        "GET,    /v1/organizations/0f8c/members,                                     identity, 8090",
        "DELETE, /v1/organizations/0f8c/members/1a,                                  identity, 8090",
        "POST,   /v1/organizations/0f8c/members/1a/password-reset,                   identity, 8090",
        "DELETE, /v1/organizations/0f8c/members/1a/sessions,                         identity, 8090",
        "GET,    /v1/organizations/0f8c/audit-logs,                                  identity, 8090",
        "POST,   /v1/organizations/0f8c/invitations,                                 identity, 8090",
        "DELETE, /v1/organizations/0f8c/invitations/1a,                              identity, 8090",
        "PUT,    /v1/organizations/0f8c/purchase-limits,                             identity, 8090",
        "GET,    /v1/organizations/0f8c/staff,                                       identity, 8090",
        "GET,    /v1/organizations/0f8c/staff/me/events,                             identity, 8090",
        "PUT,    /v1/organizations/0f8c/staff/1a/events,                             identity, 8090",
        "POST,   /v1/organizations/0f8c/staff/1a/invitation/resend,                  identity, 8090",
        "POST,   /v1/platform/organizations,                                         identity, 8090",
        "POST,   /v1/platform/organizations/0f8c/suspend,                            identity, 8090",
        "POST,   /v1/platform/organizations/0f8c/members,                            identity, 8090",
        "POST,   /v1/platform/users/0f8c/disable,                                    identity, 8090",
        "DELETE, /v1/platform/users/0f8c/sessions/sid-1,                             identity, 8090",
        "GET,    /v1/platform/audit-logs,                                            identity, 8090"
    })
    @DisplayName("mỗi endpoint công khai đi tới đúng service, qua đúng route")
    void dinh_tuyen(String method, String path, String routeId, int port) {
        Optional<RouteDef> route = match(HttpMethod.valueOf(method), path);

        assertThat(route).as("Không route nào khớp %s %s", method, path).isPresent();
        assertThat(route.get().id()).isEqualTo(routeId);
        assertThat(route.get().uri()).isEqualTo(URI.create("http://localhost:" + port));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/platform/payouts",
                "/v1/platform/payouts/0f8c/approve",
                "/v1/platform/payouts/0f8c/mark-completed"
            })
    @DisplayName("lô chi trả của payout-service có route tới payout-service (8096)")
    void chi_tra_co_route(String path) {
        // PlatformPayoutController nằm ở /v1/platform/payouts nhưng bảng route không có mẫu nào cho
        // nó (và không có route nào dùng PAYOUT_URL, dù deploy/compose/prod.yml cấp biến đó). Mọi
        // lời gọi từ trình duyệt nhận 404 của chính gateway.
        Optional<RouteDef> route = match(HttpMethod.POST, path);

        assertThat(route).as("Không route nào khớp POST %s", path).isPresent();
        assertThat(route.get().uri().getPort()).isEqualTo(8096);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Open Host Service của từng service — danh sách lấy từ @RequestMapping("/internal/...")
                "/internal/memberships",
                "/internal/users/0f8c",
                "/internal/audit-logs",
                "/internal/checkin-assignments/0f8c",
                "/internal/checkin-assignments/0f8c/users/1a",
                "/internal/tickets",
                "/internal/reservations",
                "/internal/reservations/0f8c/settle",
                "/internal/sessions/0f8c",
                "/internal/sessions/0f8c/pricing",
                "/internal/sessions/0f8c/seat-status",
                "/internal/orders/0f8c",
                "/internal/orders/statuses",
                "/internal/orders/0f8c/confirm-payment",
                "/internal/payment-intents",
                "/internal/payment-intents/0f8c/simulate-transfer",
                "/internal/payos/confirm-webhook",
                "/internal/events/trending",
                "/internal/organizations/0f8c/sales",
                // Cổng quản trị cũng không được lộ qua cổng công khai.
                "/actuator/prometheus",
                "/actuator/env"
            })
    @DisplayName("/internal/** và actuator KHÔNG khớp route nào với bất kỳ phương thức nào")
    void noi_bo_khong_lo_ra(String path) {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            assertThat(match(method, path))
                    .as("%s %s không được có route ra ngoài", method, path)
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/organizations/0f8c/../../internal/memberships",
                "/v1/events/../../internal/reservations",
                "/v1/events/%2e%2e/%2e%2e/internal/reservations",
                "/v1/me/..%2F..%2Finternal/memberships",
                "/v1/orders/0f8c/..;/..;/internal/orders/0f8c"
            })
    @DisplayName("đường dẫn dot-segment khớp route công khai — lớp chặn phải nằm ở tường lửa của chuỗi bảo mật")
    void dot_segment_khop_route_cong_khai(String path) {
        // Ghim một sự thật về gateway, không phải một tính năng: predicate Path KHÔNG chuẩn hoá
        // dot-segment, nên những đường này khớp một route công khai và sẽ được chuyển nguyên văn tới
        // service phía sau — nơi Tomcat chuẩn hoá chúng thành /internal/... Lớp chặn duy nhất là
        // StrictServerWebExchangeFirewall của Spring Security (xem GatewaySecurityTest). Ai đó tắt
        // tường lửa "cho tiện" thì bài này nhắc vì sao không được.
        assertThat(match(HttpMethod.GET, path)).isPresent();
    }

    @Test
    @DisplayName("không route nào khai mẫu bắt mọi thứ hay mẫu bắt đầu bằng /internal")
    void khong_co_mau_bat_moi_thu() {
        assertThat(routes).isNotEmpty();
        for (RouteDef route : routes) {
            assertThat(route.paths())
                    .as("route %s", route.id())
                    .noneMatch(pattern -> pattern.equals("/**")
                            || pattern.startsWith("/internal")
                            || pattern.startsWith("/actuator"));
        }
    }

    @Test
    @DisplayName(
            "route hẹp mang hạn mức riêng đứng TRƯỚC route rộng cùng đích — đảo thứ tự là mất hạn mức trong im lặng")
    void thu_tu_route_hep_truoc() {
        List<String> ids = routes.stream().map(RouteDef::id).toList();

        assertThat(ids.indexOf("inventory-hold-create")).isLessThan(ids.indexOf("inventory"));
        assertThat(ids.indexOf("ai-chat-turn")).isLessThan(ids.indexOf("ai-chatbox"));
        assertThat(ids.indexOf("ledger")).isLessThan(ids.indexOf("identity"));
        assertThat(ids.indexOf("catalog")).isLessThan(ids.indexOf("identity"));
        assertThat(ids.indexOf("ticketing")).isLessThan(ids.indexOf("ordering"));
        assertThat(ids.get(ids.size() - 1))
                .as("identity rộng nhất nên đứng cuối")
                .isEqualTo("identity");
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("GIỮ ghế bằng phương thức khác POST không lọt vào route giành ghế, cũng không có route nào khác")
    void giu_ghe_sai_phuong_thuc() {
        assertThat(match(HttpMethod.GET, "/v1/sessions/0f8c/holds"))
                .map(RouteDef::id)
                .contains("inventory");
        assertThat(match(HttpMethod.GET, "/v1/chat/agent/support"))
                .map(RouteDef::id)
                .contains("ai-chatbox");
    }
}
