// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;

/**
 * Khoá dùng để đếm hạn mức.
 *
 * <h2>Vì sao đây là bài kiểm quan trọng nhất của gateway</h2>
 *
 * <p>Rate limit chỉ có tác dụng khi <b>khoá đếm không do người gọi tự chọn</b>. {@code
 * X-Forwarded-For} là một header thường, ai cũng đặt được — nên nếu resolver tin nguyên chuỗi ấy
 * thì một con bot chỉ cần thêm một địa chỉ ngẫu nhiên vào đầu mỗi request là có hạn mức mới mỗi
 * lần. Rate limit vẫn "bật", vẫn không chặn gì. Trong một đợt mở bán, đó là khác biệt giữa một
 * hàng đợi công bằng và một sàn vé bị quét sạch trong vài giây.
 *
 * <p>Không có Spring context, không có Redis: câu hỏi ở đây thuần tuý là "khoá được tính ra thế
 * nào", và trả lời nó trong vài mili giây thì bài kiểm này sẽ luôn được chạy.
 */
class RateLimitKeyResolverTest {

    /** Hạ tầng thật: đúng một proxy (ingress) đứng trước gateway. */
    private static final int TRUSTED_PROXIES = 1;

    /** Spring không có hằng số cho header này. */
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Địa chỉ của chính khách, do ingress ghi thêm vào CUỐI chuỗi.
     *
     * <p>Thứ tự này là điều dễ dựng sai nhất khi viết bài kiểm: mỗi proxy <b>nối thêm vào cuối</b>
     * địa chỉ mà nó nhìn thấy. Nên mục cuối là mục đáng tin nhất, còn mọi mục phía trước có thể do
     * người gọi tự gõ. Đặt ngược lại thì bài kiểm vẫn xanh nhưng đang kiểm một hạ tầng không tồn
     * tại.
     */
    private static final String CLIENT = "203.0.113.5";

    private static final String INGRESS = "10.0.0.9";

    @Test
    @DisplayName("bot tự thêm địa chỉ vào đầu X-Forwarded-For KHÔNG đổi được khoá")
    void khong_the_gia_mao_de_doi_khoa() {
        // Người gọi tự đặt header; ingress nối địa chỉ thật của họ vào sau. Mỗi lần bịa một địa chỉ
        // khác nhau — nếu ăn thua thì mỗi request là một hạn mức mới, tức là không còn hạn mức nào.
        String first = resolve("1.1.1.1, " + CLIENT);
        String second = resolve("2.2.2.2, " + CLIENT);

        assertThat(first).isEqualTo(second).isEqualTo("ip:" + CLIENT);
    }

    @Test
    @DisplayName("nhồi thật nhiều địa chỉ giả cũng vậy")
    void nhoi_nhieu_dia_chi_gia() {
        assertThat(resolve("9.9.9.9, 8.8.8.8, 7.7.7.7, 6.6.6.6, " + CLIENT)).isEqualTo("ip:" + CLIENT);
    }

    @Test
    @DisplayName("khách vãng lai khác nhau được tách hạn mức riêng")
    void khach_vang_lai_tach_theo_ip_that() {
        // Không tách được thì cả internet dùng chung một hạn mức, và một người dò tự động làm mọi
        // người khác không mua được vé — hỏng theo hướng ngược lại, nhưng cũng là hỏng.
        assertThat(resolve(CLIENT)).isNotEqualTo(resolve("203.0.113.6"));
        assertThat(resolve(CLIENT)).isEqualTo("ip:" + CLIENT);
    }

    @Test
    @DisplayName("hai người dùng khác nhau sau cùng một NAT vẫn phải tách khoá")
    void nguoi_dung_dang_nhap_tach_theo_danh_tinh() {
        // Cả văn phòng ra internet bằng một IP. Tính theo IP thì người đầu tiên tra vé làm cả công
        // ty bị chặn — nên người đã đăng nhập phải được đếm theo danh tính.
        String anh = resolveAuthenticated("user-anh");
        String binh = resolveAuthenticated("user-binh");

        assertThat(anh).isNotEqualTo(binh);
        assertThat(anh).isEqualTo("user:user-anh");
    }

    @Test
    @DisplayName("khai THỪA số proxy tin cậy thì giả mạo ăn thua — ghim lại mối nguy")
    void khai_thua_proxy_thi_gia_mao_an_thua() {
        // Không phải kiểm một tính năng mà ghim một mối nguy: `trusted-proxy-count` phải khớp hạ
        // tầng thật. Khai 2 trong khi chỉ có 1 ingress thì địa chỉ do người gọi tự gõ trở thành
        // địa chỉ được tin, và hạn mức bốc hơi — trong im lặng, vì không có lỗi nào.
        //
        // Đây là bài kiểm sẽ đỏ nếu có người "sửa" mặc định thành một con số lớn cho tiện.
        assertThat(keyOf(exchange("1.1.1.1, " + CLIENT), 2)).isEqualTo("ip:1.1.1.1");
        assertThat(keyOf(exchange("1.1.1.1, " + CLIENT), TRUSTED_PROXIES)).isEqualTo("ip:" + CLIENT);
    }

    // --- dựng dữ liệu -------------------------------------------------------

    private static ServerWebExchange exchange(String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/v1/events")
                .header(FORWARDED_FOR, forwardedFor)
                .remoteAddress(new InetSocketAddress(INGRESS, 40000)));
    }

    private static String resolve(String forwardedFor) {
        return keyOf(exchange(forwardedFor), TRUSTED_PROXIES);
    }

    /**
     * Cùng một request, nhưng có người dùng đã đăng nhập.
     *
     * <p>{@code MockServerWebExchange.getPrincipal()} luôn rỗng, nên phải bọc lại để trả về một
     * principal. Không bọc mà tự dựng chuỗi {@code "user:" + tên} thì bài kiểm chỉ đang so hai
     * chuỗi do chính nó tạo ra — resolver có hỏng cũng vẫn xanh.
     */
    private static String resolveAuthenticated(String username) {
        return keyOf(
                new ServerWebExchangeDecorator(exchange(CLIENT)) {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends Principal> Mono<T> getPrincipal() {
                        return (Mono<T>) Mono.just(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
                    }
                },
                TRUSTED_PROXIES);
    }

    private static String keyOf(ServerWebExchange exchange, int trustedProxies) {
        RateLimitConfig config = new RateLimitConfig();
        ReflectionTestUtils.setField(config, "trustedProxyCount", trustedProxies);
        return config.principalOrIpKeyResolver().resolve(exchange).block();
    }
}
