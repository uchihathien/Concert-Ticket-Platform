// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS cho bốn app Next.js gọi thẳng gateway từ trình duyệt.
 *
 * <p>Thiếu cấu hình này thì <b>mọi</b> lời gọi API từ trình duyệt đều hỏng — và hỏng theo kiểu khó
 * lần nhất: request đi tới nơi, backend xử lý xong, rồi trình duyệt vứt phản hồi đi và chỉ nói
 * "CORS error" ở console. Log phía server hoàn toàn sạch, nên nhìn từ backend thì mọi thứ trông
 * như đang chạy tốt.
 *
 * <h3>Vì sao là bean của Spring Security, không phải {@code globalcors} trong YAML</h3>
 *
 * <p>Chuỗi filter của gateway đòi xác thực với {@code anyExchange}. Preflight {@code OPTIONS} theo
 * chuẩn CORS <b>không mang</b> header {@code Authorization} — trình duyệt cố ý không gửi. Nên nếu
 * CORS được xử lý sau Spring Security, preflight nhận 401 và request thật không bao giờ được gửi.
 *
 * <p>Khai bằng {@code globalcors} trong YAML tạo một {@code CorsWebFilter} với order mặc định 0,
 * còn {@code WebFilterChainProxy} của Spring Security nằm ở {@code HIGHEST_PRECEDENCE + 100} — tức
 * là chạy TRƯỚC. Bean {@link CorsConfigurationSource} cộng với {@code .cors()} ở chuỗi bảo mật thì
 * ngược lại: CORS nằm bên trong chuỗi, trước bước uỷ quyền, nên preflight được trả lời mà không
 * cần token.
 *
 * <p>Chỉ có MỘT nguồn cấu hình CORS. Bật cả hai sẽ khiến phản hồi mang hai header
 * {@code Access-Control-Allow-Origin}, và trình duyệt từ chối phản hồi có header trùng — lỗi trông
 * y hệt như chưa cấu hình gì.
 */
@Configuration
public class CorsConfig {

    /**
     * Origin được phép, liệt kê tường minh chứ không dùng {@code *}.
     *
     * <p>Bốn app chạy bốn cổng khác nhau ở dev: khách 3000, tổ chức 3001, soát vé 3002, nền tảng
     * 3003. Ở production thay bằng biến môi trường.
     */
    private final List<String> allowedOrigins;

    public CorsConfig(
            @Value(
                            "${nexaticket.gateway.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:3002,http://localhost:3003}")
                    List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));

        // Danh sách này khớp đúng những header mà `packages/ts-sdk` gửi đi. Không dùng "*": với
        // allowCredentials=false thì "*" hợp lệ, nhưng liệt kê tường minh khiến việc thêm một
        // header mới ở client trở thành một thay đổi có ý thức ở đây, thay vì im lặng đi qua.
        cors.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type", "Idempotency-Key", "If-None-Match"));

        // BẮT BUỘC, và đây là chỗ dễ bỏ sót nhất.
        //
        // Cross-origin, JavaScript chỉ đọc được bảy header mặc định của CORS — ETag và
        // X-Correlation-Id KHÔNG nằm trong số đó. Thiếu dòng này thì:
        //   - `fetchSeatMap` luôn thấy ETag null, không bao giờ gửi If-None-Match, và mỗi lần
        //     kiểm tra tồn kho lại kéo về ~400KB thay vì một phản hồi 304 rỗng;
        //   - correlation id không hiện trong báo lỗi, nên người dùng không có mã nào để đọc cho
        //     bộ phận hỗ trợ.
        // Cả hai đều không sinh ra lỗi nào — chúng chỉ âm thầm chạy chậm và mất dấu vết.
        cors.setExposedHeaders(List.of("ETag", "X-Correlation-Id"));

        // Token đi trong header Authorization, không phải cookie. Không bật credentials nghĩa là
        // một trang lạ có dụ được trình duyệt gọi API thì cũng không kèm theo phiên của người dùng.
        cors.setAllowCredentials(false);

        // Không có dòng này thì mỗi lời gọi API là hai vòng khứ hồi. Một giờ là mức Chrome chấp
        // nhận; Safari tự kẹp xuống thấp hơn và điều đó không sao.
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
