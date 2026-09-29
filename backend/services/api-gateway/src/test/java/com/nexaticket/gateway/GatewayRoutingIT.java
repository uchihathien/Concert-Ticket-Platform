// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.platform.test.RedisSingleton;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bảng route của gateway: đường nào đi tới service nào, và đường nào mang hạn mức riêng.
 *
 * <h2>Vì sao bảng này cần test hơn gần như mọi thứ khác trong repo</h2>
 *
 * <p>Gateway khớp theo <b>thứ tự khai báo</b> và route đầu tiên khớp là route thắng. Sai thứ tự
 * <b>không sinh ra lỗi cấu hình nào</b> — file YAML vẫn hợp lệ, ứng dụng vẫn khởi động. Nó chỉ lặng
 * lẽ gửi request sang sai chỗ. Chuyện ấy đã xảy ra thật ít nhất hai lần (xem ghi chú trong
 * {@code application.yml}: {@code /v1/me/tickets} rơi vào identity, và trang Doanh thu nhận 404 của
 * chính gateway).
 *
 * <p>Hai route tách sau này còn tệ hơn một bậc: {@code inventory-hold-create} và
 * {@code ai-chat-turn} trỏ tới <b>đúng service</b> như route rộng ngay sau chúng. Đảo thứ tự thì
 * request vẫn tới đúng nơi, vẫn trả đúng kết quả, không ai thấy gì — chỉ có hạn mức chặt là biến
 * mất. Không có bài kiểm này thì không có cách nào phát hiện.
 *
 * <h2>Vì sao cần Redis</h2>
 *
 * <p>{@code RequestRateLimiter} dựng bean đếm token trên Redis lúc khởi động context. Không có
 * Redis thì context không lên, và bảng route không tồn tại để mà kiểm.
 */
@SpringBootTest(
        properties = {
            // `issuer-uri` bắt Spring đi tải cấu hình OIDC NGAY lúc khởi động — tức là test này sẽ
            // cần một Keycloak đang chạy chỉ để đọc một bảng cấu hình. `jwk-set-uri` thì lười: khoá
            // chỉ được tải ở lần đầu có token cần kiểm, và ở đây không có token nào.
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri="
        })
class GatewayRoutingIT {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisSingleton.bind(registry);
    }

    @Autowired
    private RouteLocator routes;

    /**
     * Định nghĩa thô của route, để đọc tham số của bộ lọc.
     *
     * <p>{@code RouteLocator} trả về filter ĐÃ dựng thành bean — {@code toString()} của chúng
     * không hứa gì về việc còn giữ lại tham số gốc. {@code RouteDefinitionLocator} giữ nguyên
     * những gì viết trong YAML, nên khẳng định về ngưỡng dựa vào nguồn chắc chắn thay vì vào một
     * chuỗi có thể đổi giữa hai phiên bản Spring.
     */
    @Autowired
    private RouteDefinitionLocator definitions;

    @Test
    @DisplayName("lượt GIÀNH GHẾ đi vào route có hạn mức riêng, không rơi vào route inventory chung")
    void hold_create_co_han_muc_rieng() {
        Route route = match(HttpMethod.POST, "/v1/sessions/0f8c/holds");

        assertThat(route.getId()).isEqualTo("inventory-hold-create");
        // Giá trị ĐÃ phân giải, không phải chuỗi placeholder: đây mới là hạn mức thật sự có hiệu
        // lực, và khẳng định như vậy cũng kiểm luôn rằng giá trị mặc định của biến môi trường đúng.
        assertThat(rateLimit(route)).isEqualTo("5");
    }

    @Test
    @DisplayName("ĐỌC sơ đồ ghế KHÔNG bị dính hạn mức của lượt giành ghế")
    void doc_so_do_khong_bi_bop() {
        // Cùng tiền tố `/v1/sessions/`, nhưng đây là đường đọc có ETag — phần lớn lượt gọi trả 304
        // rỗng. Bóp nó xuống 5 rps là làm chậm đúng màn hình mà mọi người đang nhìn.
        Route route = match(HttpMethod.GET, "/v1/sessions/0f8c/seats");

        assertThat(route.getId()).isEqualTo("inventory");
        assertThat(rateLimit(route)).isNull();
    }

    @Test
    @DisplayName("NHẢ ghế không bị bóp — đường này trả ghế về cho người khác mua")
    void nha_ghe_khong_bi_bop() {
        Route route = match(HttpMethod.DELETE, "/v1/holds/0f8c");

        assertThat(route.getId()).isEqualTo("inventory");
        assertThat(rateLimit(route)).isNull();
    }

    @Test
    @DisplayName("lượt chat tốn tiền có hạn mức riêng, phần còn lại của trợ lý thì không")
    void chat_ton_tien_co_han_muc_rieng() {
        Route turn = match(HttpMethod.POST, "/v1/chat/agent/support");
        assertThat(turn.getId()).isEqualTo("ai-chat-turn");
        assertThat(rateLimit(turn)).isEqualTo("1");

        // Đọc lại đoạn hội thoại không gọi mô hình, nên dùng ngưỡng chung.
        Route history = match(HttpMethod.GET, "/v1/chat/agent/sessions/0f8c/messages");
        assertThat(history.getId()).isEqualTo("ai-chatbox");
        assertThat(rateLimit(history)).isNull();
    }

    @Test
    @DisplayName("bàn hỗ trợ của nhân viên không bị bóp xuống tốc độ gõ phím")
    void ban_ho_tro_khong_bi_bop() {
        // Người trực nhận phiếu, trả lời, đóng phiếu — liên tục. 1 rps ở đây là làm hỏng công việc
        // của chính người đang dọn hậu quả cho trợ lý.
        Route route = match(HttpMethod.POST, "/v1/support/handoffs/0f8c/claim");

        assertThat(route.getId()).isEqualTo("ai-chatbox");
        assertThat(rateLimit(route)).isNull();
    }

    @Test
    @DisplayName("những cặp chồng lấn cũ vẫn đi đúng chỗ")
    void cac_cap_chong_lan_cu() {
        // Mỗi dòng ở đây từng là một sự cố thật hoặc là hàng xóm trực tiếp của một sự cố thật.
        assertThat(match(HttpMethod.GET, "/v1/me/tickets").getId()).isEqualTo("ticketing");
        assertThat(match(HttpMethod.GET, "/v1/me/orders").getId()).isEqualTo("ordering");
        assertThat(match(HttpMethod.GET, "/v1/me").getId()).isEqualTo("identity");
        assertThat(match(HttpMethod.GET, "/v1/orders/0f8c/tickets").getId()).isEqualTo("ticketing");
        assertThat(match(HttpMethod.GET, "/v1/sessions/0f8c/checkins").getId()).isEqualTo("ticketing");
        assertThat(match(HttpMethod.GET, "/v1/platform/organizations/0f8c/balance")
                        .getId())
                .isEqualTo("ledger");
        assertThat(match(HttpMethod.GET, "/v1/organizations/0f8c/tickets").getId())
                .isEqualTo("ticketing");
        assertThat(match(HttpMethod.GET, "/v1/organizations/0f8c/uploads/poster")
                        .getId())
                .isEqualTo("catalog");
        assertThat(match(HttpMethod.GET, "/v1/admin/revenue").getId()).isEqualTo("analytics");
    }

    @Test
    @DisplayName("/internal/** KHÔNG có route nào — đây là ranh giới bảo mật, không phải thiếu sót")
    void internal_khong_lo_ra_ngoai() {
        // `/internal/reservations` đổi trạng thái tồn kho mà KHÔNG kiểm chủ sở hữu như đường công
        // khai, vì nó tin rằng chỉ service khác gọi được. Một route trỏ tới đây là mất kiểm soát
        // ghế — và sẽ không có triệu chứng nào cho tới khi có người tìm ra.
        assertThat(matchOrNull(HttpMethod.POST, "/internal/reservations")).isNull();
        assertThat(matchOrNull(HttpMethod.GET, "/internal/sessions/0f8c/seat-status"))
                .isNull();
        assertThat(matchOrNull(HttpMethod.GET, "/actuator/prometheus")).isNull();
    }

    // --- dựng dữ liệu -------------------------------------------------------

    /**
     * Route đầu tiên khớp, đúng thứ tự gateway duyệt.
     *
     * <p>{@code RouteLocator} trả về theo đúng thứ tự khai báo, nên lấy phần tử khớp đầu tiên là
     * mô phỏng đúng hành vi thật — bao gồm cả cái quy tắc "hẹp trước rộng" đang được kiểm.
     */
    private Route match(HttpMethod method, String path) {
        Route route = matchOrNull(method, path);
        assertThat(route).as("Không route nào khớp %s %s", method, path).isNotNull();
        return route;
    }

    private Route matchOrNull(HttpMethod method, String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path).build());

        List<Route> all = routes.getRoutes().collectList().block();
        assertThat(all).isNotNull();
        return all.stream()
                // `apply` trả Publisher chứ không trả Mono, nên phải bọc lại mới chờ được.
                .filter(route -> Boolean.TRUE.equals(
                        Mono.from(route.getPredicate().apply(exchange)).block()))
                .findFirst()
                .orElse(null);
    }

    /** Ngưỡng của bộ giới hạn RIÊNG trên route, hoặc null nếu route chỉ dùng sàn chung. */
    private String rateLimit(Route route) {
        return definitions.getRouteDefinitions().collectList().block().stream()
                .filter(definition -> definition.getId().equals(route.getId()))
                .flatMap(definition -> definition.getFilters().stream())
                .filter(filter -> "RequestRateLimiter".equals(filter.getName()))
                .map(FilterDefinition::getArgs)
                .map(args -> args.get("redis-rate-limiter.replenishRate"))
                .findFirst()
                .orElse(null);
    }
}
