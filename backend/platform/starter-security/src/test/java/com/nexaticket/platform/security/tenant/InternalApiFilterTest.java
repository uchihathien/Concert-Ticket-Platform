// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.platform.security.InternalApiProperties;
import jakarta.servlet.FilterChain;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link InternalApiFilter}: bí mật dùng chung cho {@code /internal/**}.
 *
 * <p>Thiếu hay sai bí mật đều nhận <b>404</b> — 401 sẽ xác nhận endpoint tồn tại. Đường ngoài
 * {@code /internal/} không bị đụng tới.
 */
class InternalApiFilterTest {

    private static final String SECRET = "bi-mat-noi-bo-9f3c";

    private final InternalApiFilter filter = new InternalApiFilter(SECRET);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private boolean chainCalled;
    private final FilterChain chain = (req, res) -> chainCalled = true;

    private void run(String uri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            request.addHeader(InternalApiFilter.HEADER, token);
        }
        filter.doFilter(request, response, chain);
    }

    @Nested
    @DisplayName("Đường /internal/**")
    class InternalPaths {

        @Test
        @DisplayName("đúng bí mật: cho qua")
        void dung_bi_mat_cho_qua() throws Exception {
            run("/internal/memberships", SECRET);

            assertThat(chainCalled).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("thiếu header: 404, request không tới controller")
        void thieu_header_404() throws Exception {
            run("/internal/memberships", null);

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(chainCalled).isFalse();
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(
                strings = {
                    "",
                    "sai",
                    // Tiền tố đúng — so sánh từng ký tự rồi dừng sớm sẽ "gần đúng" ở đây.
                    "bi-mat-noi-bo-9f3",
                    // Bí mật đúng cộng thêm đuôi.
                    "bi-mat-noi-bo-9f3cX",
                    // Cùng độ dài, khác một ký tự cuối.
                    "bi-mat-noi-bo-9f3d",
                    // Khác hoa thường.
                    "BI-MAT-NOI-BO-9F3C",
                    // Khoảng trắng bao quanh không được cắt bỏ hộ người gọi.
                    " bi-mat-noi-bo-9f3c "
                })
        @DisplayName("sai bí mật dưới mọi dạng: 404")
        void sai_bi_mat_404(String token) throws Exception {
            run("/internal/reservations/42", token);

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("phản hồi từ chối không mang thân nào nhắc tới bí mật hay header")
        void phan_hoi_khong_lo_gi() throws Exception {
            run("/internal/memberships", "sai");

            assertThat(response.getContentAsString()).doesNotContain(SECRET).doesNotContain(InternalApiFilter.HEADER);
        }
    }

    @Nested
    @DisplayName("Đường khác")
    class OtherPaths {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"/v1/events", "/internal", "/internals/x", "/v1/internal/x", "/actuator/health"})
        @DisplayName("không bắt đầu bằng /internal/: filter không đụng tới, kể cả khi không có header")
        void khong_dung_toi_duong_khac(String uri) throws Exception {
            run(uri, null);

            assertThat(chainCalled).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("bí mật có ký tự ngoài ASCII được so theo UTF-8")
    void bi_mat_unicode() throws Exception {
        InternalApiFilter unicode = new InternalApiFilter("mật-khẩu-đặc-biệt");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/x");
        request.addHeader(InternalApiFilter.HEADER, "mật-khẩu-đặc-biệt");

        unicode.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
    }

    /**
     * Không đo được thời gian một cách tất định trong unit test, nên ghim <b>cách so sánh</b>: mã
     * nguồn phải dùng {@code MessageDigest.isEqual} và không dùng {@code equals} trên bí mật. Đổi
     * về {@code equals} sẽ lộ độ dài tiền tố khớp qua thời gian phản hồi.
     */
    @Test
    @DisplayName("so sánh bí mật bằng MessageDigest.isEqual (thời gian hằng định), không bằng equals")
    void so_sanh_thoi_gian_hang_dinh() throws Exception {
        Path source = Path.of("src/main/java/com/nexaticket/platform/security/tenant/InternalApiFilter.java");
        String code = Files.readString(source);

        assertThat(code).contains("MessageDigest.isEqual(");
        assertThat(code).doesNotContainPattern("presented\\s*\\.\\s*equals\\(").doesNotContain(".equals(expected");
    }

    @Nested
    @DisplayName("InternalApiProperties")
    class Properties {

        @Test
        @DisplayName("bí mật null, rỗng hay chỉ khoảng trắng: không bật filter")
        void bi_mat_trong_khong_bat() {
            assertThat(new InternalApiProperties(null).enforced()).isFalse();
            assertThat(new InternalApiProperties("").enforced()).isFalse();
            assertThat(new InternalApiProperties("   ").enforced()).isFalse();
        }

        @Test
        @DisplayName("có bí mật: bật filter")
        void co_bi_mat_thi_bat() {
            assertThat(new InternalApiProperties(SECRET).enforced()).isTrue();
        }
    }
}
