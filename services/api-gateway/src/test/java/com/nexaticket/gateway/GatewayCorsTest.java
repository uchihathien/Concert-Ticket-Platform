// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Chính sách CORS của gateway.
 *
 * <p>Ba thứ ở đây hỏng theo ba kiểu khác nhau và không kiểu nào tự lộ ra:
 *
 * <ul>
 *   <li><b>Origin quá rộng</b> — không có lỗi nào, chỉ là một trang bất kỳ gọi được API;
 *   <li><b>Thiếu exposed header</b> — ETag thành null ở client, nên mỗi lần kiểm tồn kho kéo về
 *       cả tấm sơ đồ thay vì một phản hồi 304 rỗng. Không lỗi, chỉ chậm;
 *   <li><b>Bật allowCredentials</b> — trang lạ dụ được trình duyệt gửi kèm phiên của người dùng.
 * </ul>
 */
class GatewayCorsTest {

    private static final List<String> ORIGINS = List.of("https://ve.nexaticket.vn", "https://admin.nexaticket.vn");

    @Test
    @DisplayName("chỉ những origin đã khai mới được chấp nhận")
    void chi_origin_da_khai() {
        CorsConfiguration cors = configFor("/v1/events");

        assertThat(cors.checkOrigin("https://ve.nexaticket.vn")).isEqualTo("https://ve.nexaticket.vn");
        assertThat(cors.checkOrigin("https://ke-tan-cong.example")).isNull();
        // Một tên miền con khác KHÔNG tự động được phép — đây là chỗ hay bị nhầm.
        assertThat(cors.checkOrigin("https://evil.nexaticket.vn")).isNull();
    }

    @Test
    @DisplayName("không dùng ký tự đại diện cho origin")
    void khong_dung_dai_dien() {
        CorsConfiguration cors = configFor("/v1/events");

        assertThat(cors.getAllowedOrigins()).isNotNull().doesNotContain("*");
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
    }

    @Test
    @DisplayName("ETag và correlation id đọc được từ JavaScript")
    void lo_ra_dung_hai_header() {
        // Cross-origin, JavaScript chỉ đọc được bảy header mặc định — ETag không nằm trong số đó.
        // Thiếu dòng khai này thì If-None-Match không bao giờ được gửi và sơ đồ ghế tải lại toàn bộ
        // ở MỖI lần kiểm tồn kho, giữa lúc đông người nhất.
        assertThat(configFor("/v1/sessions/x/seats").getExposedHeaders()).contains("ETag", "X-Correlation-Id");
    }

    @Test
    @DisplayName("không gửi kèm cookie/phiên qua cross-origin")
    void khong_bat_credentials() {
        // Token đi trong header Authorization. Bật credentials là mở đường cho một trang lạ dùng
        // phiên đăng nhập của khách.
        assertThat(configFor("/v1/me/orders").getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Idempotency-Key được phép gửi lên")
    void cho_phep_idempotency_key() {
        // SDK gửi header này ở mọi lệnh ghi. Chặn nó ở CORS thì mọi lần đặt vé từ trình duyệt hỏng
        // ngay ở bước preflight — và thông báo lỗi sẽ không nhắc gì tới CORS.
        assertThat(configFor("/v1/holds").checkHeaders(List.of("Idempotency-Key", "Authorization")))
                .isNotNull();
    }

    private static CorsConfiguration configFor(String path) {
        return new CorsConfig(ORIGINS)
                .corsConfigurationSource()
                .getCorsConfiguration(MockServerWebExchange.from(MockServerHttpRequest.get(path)));
    }
}
