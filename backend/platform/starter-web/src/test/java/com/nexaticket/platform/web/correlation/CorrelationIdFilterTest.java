// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.id.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link CorrelationIdFilter}: nhận lại hoặc sinh {@code X-Correlation-Id} ở biên HTTP.
 *
 * <p>Ba điều phải đúng cùng lúc: trong lúc xử lý, {@link CorrelationContext} và MDC mang đúng mã;
 * phản hồi trả lại đúng mã đó; và khi request kết thúc — kể cả bằng ngoại lệ — luồng Tomcat được
 * trả lại sạch, không mang mã của request trước sang request sau.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private final AtomicReference<String> seenInContext = new AtomicReference<>();
    private final AtomicReference<String> seenInMdc = new AtomicReference<>();

    private final FilterChain recordingChain = (req, res) -> {
        seenInContext.set(CorrelationContext.current());
        seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY));
    };

    @AfterEach
    void don_dep() {
        CorrelationContext.clear();
        MDC.clear();
    }

    private static MockHttpServletRequest requestWithHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/events");
        if (value != null) {
            request.addHeader(CorrelationIdFilter.HEADER, value);
        }
        return request;
    }

    @Test
    @DisplayName("mã hợp lệ từ client được giữ nguyên: context, MDC và header phản hồi cùng một giá trị")
    void giu_nguyen_ma_hop_le() throws Exception {
        filter.doFilter(requestWithHeader("01HZX-abc-123"), response, recordingChain);

        assertThat(seenInContext.get()).isEqualTo("01HZX-abc-123");
        assertThat(seenInMdc.get()).isEqualTo("01HZX-abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("01HZX-abc-123");
    }

    @Test
    @DisplayName("không có header: sinh mã mới 26 ký tự Crockford Base32 và trả lại trong phản hồi")
    void khong_co_header_thi_sinh_moi() throws Exception {
        filter.doFilter(requestWithHeader(null), response, recordingChain);

        assertThat(seenInContext.get()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seenInContext.get());
        assertThat(seenInMdc.get()).isEqualTo(seenInContext.get());
    }

    @ParameterizedTest(name = "[{index}] độ dài {0}")
    @ValueSource(ints = {0, 65, 4096})
    @DisplayName("header trống hoặc dài quá 64 ký tự: bỏ, sinh mã mới")
    void header_trong_hoac_qua_dai_bi_bo(int length) throws Exception {
        String incoming = "a".repeat(length);

        filter.doFilter(requestWithHeader(incoming), response, recordingChain);

        assertThat(seenInContext.get()).isNotEqualTo(incoming).hasSize(26);
    }

    @Test
    @DisplayName("header chỉ có khoảng trắng: bỏ, sinh mã mới")
    void header_khoang_trang_bi_bo() throws Exception {
        filter.doFilter(requestWithHeader("   "), response, recordingChain);

        assertThat(seenInContext.get()).hasSize(26).isNotBlank();
    }

    @Test
    @DisplayName("đúng 64 ký tự là biên còn được chấp nhận")
    void dung_64_ky_tu_duoc_chap_nhan() throws Exception {
        String incoming = "b".repeat(64);

        filter.doFilter(requestWithHeader(incoming), response, recordingChain);

        assertThat(seenInContext.get()).isEqualTo(incoming);
    }

    @Test
    @DisplayName("hai request liên tiếp không có header nhận hai mã khác nhau")
    void hai_request_hai_ma() throws Exception {
        filter.doFilter(requestWithHeader(null), response, recordingChain);
        String first = seenInContext.get();

        filter.doFilter(requestWithHeader(null), new MockHttpServletResponse(), recordingChain);

        assertThat(seenInContext.get()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("sau request, context và MDC được dọn — luồng không mang mã sang request sau")
    void don_context_sau_request() throws Exception {
        filter.doFilter(requestWithHeader("corr-1"), response, recordingChain);

        assertThat(CorrelationContext.current()).isEqualTo(CorrelationContext.UNKNOWN);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("chuỗi filter ném ngoại lệ: ngoại lệ đi tiếp, nhưng context và MDC vẫn được dọn")
    void don_context_ca_khi_nem_loi() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("downstream failed");
        };

        assertThatThrownBy(() -> filter.doFilter(requestWithHeader("corr-err"), response, failing))
                .isInstanceOf(ServletException.class)
                .hasMessage("downstream failed");

        assertThat(CorrelationContext.current()).isEqualTo(CorrelationContext.UNKNOWN);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        // Phản hồi vẫn mang mã để người dùng báo lỗi có cái mà trích.
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("corr-err");
    }

    @Test
    @DisplayName("current() cũ (deprecated) đọc đúng chỗ giữ ở shared-kernel")
    @SuppressWarnings("removal")
    void current_cu_doc_tu_shared_kernel() {
        CorrelationContext.set("tu-kernel");

        assertThat(CorrelationIdFilter.current()).isEqualTo("tu-kernel");
    }

    /**
     * Mã từ client đi thẳng vào MDC (tức mọi dòng log) và vào header phản hồi mà không lọc ký tự.
     *
     * <p>Một giá trị có ký tự xuống dòng giả được một dòng log hoàn chỉnh (CWE-117) — đúng kiểu
     * chứng cứ giả mà người vận hành sẽ đọc khi tra sự cố. Bộ lọc chỉ chặn độ dài, không chặn ký tự
     * điều khiển. Ca này khẳng định hành vi đúng (bỏ, sinh mã mới) và cố ý để đỏ cho tới khi sửa.
     */
    @Test
    @DisplayName("mã chứa ký tự điều khiển (CR/LF) bị bỏ và thay bằng mã mới — chống giả mạo log")
    void ma_chua_ky_tu_dieu_khien_bi_bo() throws Exception {
        String forged = "x\nINFO  Payment 42 refunded by admin";

        filter.doFilter(requestWithHeader(forged), response, recordingChain);

        assertThat(seenInMdc.get()).doesNotContain("\n").doesNotContain("\r");
        assertThat(seenInContext.get()).isNotEqualTo(forged);
    }
}
