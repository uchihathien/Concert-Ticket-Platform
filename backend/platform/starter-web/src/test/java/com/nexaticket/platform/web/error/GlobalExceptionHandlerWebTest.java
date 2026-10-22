// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.id.CorrelationContext;
import com.nexaticket.platform.web.correlation.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link GlobalExceptionHandler} đi qua đúng đường Spring MVC dispatch, kèm {@link CorrelationIdFilter}.
 *
 * <p>Gọi thẳng method của handler chỉ kiểm được thân phản hồi; nó không kiểm được câu hỏi quan trọng
 * hơn — <b>ngoại lệ nào rơi vào handler nào</b>. Một ngoại lệ chuẩn của Spring không có handler
 * riêng sẽ bị {@code @ExceptionHandler(Exception.class)} nuốt và thành 500, và chỉ dispatch thật
 * mới cho thấy điều đó.
 *
 * <p>Mỗi phản hồi lỗi phải giữ cùng một hình dạng RFC 7807 ({@code type}, {@code title},
 * {@code status}, {@code code}, {@code detail}, {@code correlationId}) và không bao giờ mang thông
 * điệp gốc hay stack trace của ngoại lệ ra ngoài.
 */
class GlobalExceptionHandlerWebTest {

    private static final String SECRET_MESSAGE = "password=hunter2 at jdbc:postgresql://db.internal:5432/ledger";

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void dung_mock_mvc() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @AfterEach
    void don_ngu_canh() {
        CorrelationContext.clear();
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Hình dạng RFC 7807 chung cho mọi phản hồi lỗi. */
    private void assertProblemShape(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = body(result);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        String slug = code.toLowerCase().replace('_', '-');
        assertThat(body.path("type").asText()).isEqualTo("https://nexaticket.vn/errors/" + slug);
        assertThat(body.path("title").asText()).isEqualTo(slug.replace('-', ' '));
        assertThat(body.path("detail").asText()).isNotBlank();
        assertThat(body.path("correlationId").asText()).isNotBlank();
        // Không bao giờ có trường stack trace hay thông điệp ngoại lệ thô.
        assertThat(body.has("trace")).isFalse();
        assertThat(body.has("exception")).isFalse();
        assertThat(body.has("stackTrace")).isFalse();
    }

    @Nested
    @DisplayName("ApiException")
    class ApiExceptionTests {

        @Test
        @DisplayName("mã nghiệp vụ 4xx đi ra đúng trạng thái, mã, detail và meta")
        void ma_4xx_giu_nguyen_trang_thai_va_meta() throws Exception {
            MvcResult result = perform(get("/api-ex/conflict").header(CorrelationIdFilter.HEADER, "corr-409"));

            assertProblemShape(result, 409, "CONFLICT");
            JsonNode body = body(result);
            assertThat(body.path("detail").asText()).isEqualTo("Seat already held");
            assertThat(body.path("meta").path("seatId").asText()).isEqualTo("A-12");
            assertThat(body.path("correlationId").asText()).isEqualTo("corr-409");
        }

        @Test
        @DisplayName("meta rỗng thì trường meta bị bỏ hẳn khỏi JSON, không phải {} hay null")
        void meta_rong_thi_khong_co_truong_meta() throws Exception {
            MvcResult result = perform(get("/api-ex/not-found"));

            assertProblemShape(result, 404, "NOT_FOUND");
            JsonNode body = body(result);
            assertThat(body.has("meta")).isFalse();
            assertThat(body.path("detail").asText()).isEqualTo("Order not found");
        }

        @Test
        @DisplayName("forbidden() trả 403 FORBIDDEN")
        void forbidden_tra_403() throws Exception {
            assertProblemShape(perform(get("/api-ex/forbidden")), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("mã nghiệp vụ 5xx vẫn giữ mã của nó, không bị đổi thành INTERNAL_ERROR")
        void ma_5xx_giu_nguyen() throws Exception {
            assertProblemShape(perform(get("/api-ex/upstream")), 503, "UPSTREAM_UNAVAILABLE");
        }

        @Test
        @DisplayName("mã nghiệp vụ riêng của một service đi qua cùng một hình dạng")
        void ma_rieng_cua_service() throws Exception {
            MvcResult result = perform(get("/api-ex/custom"));

            assertProblemShape(result, 422, "HOLD_EXPIRED");
            assertThat(body(result).path("type").asText()).isEqualTo("https://nexaticket.vn/errors/hold-expired");
        }
    }

    @Nested
    @DisplayName("Validation thân request")
    class BodyValidation {

        @Test
        @DisplayName("@Valid hỏng: 400 VALIDATION_FAILED, meta.fields liệt kê từng trường hỏng")
        void bean_validation_liet_ke_truong() throws Exception {
            MvcResult result = perform(
                    post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"quantity\":0}"));

            assertProblemShape(result, 400, "VALIDATION_FAILED");
            JsonNode fields = body(result).path("meta").path("fields");
            assertThat(fields.has("name")).isTrue();
            assertThat(fields.has("quantity")).isTrue();
            assertThat(body(result).path("detail").asText()).isEqualTo("Request validation failed");
        }

        @Test
        @DisplayName("JSON hỏng: 400, và detail không nhắc lại lỗi parser (vị trí, lớp Java)")
        void json_hong_tra_400_khong_lo_parser() throws Exception {
            MvcResult result = perform(
                    post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\", quantity"));

            assertProblemShape(result, 400, "VALIDATION_FAILED");
            String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body(result).path("detail").asText()).isEqualTo("Malformed request body");
            assertThat(raw)
                    .doesNotContain("JsonParseException")
                    .doesNotContain("line:")
                    .doesNotContain("com.");
        }

        @Test
        @DisplayName("thân request thiếu hẳn: 400 chứ không phải 500")
        void than_trong_tra_400() throws Exception {
            MvcResult result = perform(post("/body").contentType(MediaType.APPLICATION_JSON));

            assertProblemShape(result, 400, "VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("Lưới cuối: lỗi không lường trước")
    class Unexpected {

        @Test
        @DisplayName("500 INTERNAL_ERROR, KHÔNG lộ thông điệp gốc hay stack trace")
        void khong_lo_thong_diep_goc() throws Exception {
            MvcResult result = perform(get("/boom").header(CorrelationIdFilter.HEADER, "corr-500"));

            assertProblemShape(result, 500, "INTERNAL_ERROR");
            String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(raw)
                    .doesNotContain("hunter2")
                    .doesNotContain("jdbc:")
                    .doesNotContain("IllegalStateException")
                    .doesNotContain("ThrowingController");
            assertThat(body(result).path("correlationId").asText()).isEqualTo("corr-500");
            assertThat(body(result).path("detail").asText()).contains("correlation id");
        }

        @Test
        @DisplayName("NullPointerException: 500, không lộ tên lớp ngoại lệ hay tên biến")
        void npe_khong_lo_gi() throws Exception {
            MvcResult result = perform(get("/npe"));

            assertProblemShape(result, 500, "INTERNAL_ERROR");
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("NullPointerException")
                    .doesNotContain("secretField");
        }
    }

    @Nested
    @DisplayName("Ngoại lệ hạ tầng có handler riêng")
    class InfrastructureExceptions {

        @Test
        @DisplayName("đường dẫn không tồn tại: 404 NOT_FOUND, không nhắc lại đường dẫn")
        void duong_dan_khong_ton_tai_tra_404() throws Exception {
            MvcResult result = perform(get("/internal/khong-co-dau"));

            assertProblemShape(result, 404, "NOT_FOUND");
            assertThat(body(result).path("detail").asText()).doesNotContain("khong-co-dau");
        }

        @Test
        @DisplayName("ResponseStatusException 409 từ controller: giữ 409, mã VALIDATION_FAILED, không lộ reason")
        void response_status_exception_giu_trang_thai() throws Exception {
            MvcResult result = perform(get("/status"));

            assertProblemShape(result, 409, "VALIDATION_FAILED");
            assertThat(body(result).path("detail").asText())
                    .isEqualTo("Request rejected")
                    .doesNotContain("internal");
        }

        @Test
        @DisplayName("request async quá hạn: 503 UPSTREAM_UNAVAILABLE, không phải 500")
        void async_qua_han_tra_503() throws Exception {
            MvcResult result = perform(get("/async-timeout"));

            assertProblemShape(result, 503, "UPSTREAM_UNAVAILABLE");
            assertThat(body(result).path("detail").asText()).contains("Retry");
        }
    }

    /**
     * Ngoại lệ chuẩn của Spring MVC mà {@link GlobalExceptionHandler} chưa có handler riêng.
     *
     * <p>Mọi ca ở đây đều là <b>lỗi của người gọi</b> và Spring đã biết đúng mã trạng thái cho nó.
     * Nhưng {@code ExceptionHandlerExceptionResolver} chạy TRƯỚC {@code DefaultHandlerExceptionResolver},
     * nên {@code @ExceptionHandler(Exception.class)} bắt chúng trước và trả <b>500 kèm log ERROR</b>.
     * Các test này khẳng định hành vi ĐÚNG và cố ý để đỏ cho tới khi handler được sửa.
     */
    @Nested
    @DisplayName("Ngoại lệ chuẩn của Spring MVC phải là 4xx, không phải 500")
    class SpringClientErrors {

        @Test
        @DisplayName("query param sai kiểu (MethodArgumentTypeMismatchException): 400")
        void sai_kieu_query_param_tra_400() throws Exception {
            assertProblemShape(perform(get("/typed").param("count", "abc")), 400, "VALIDATION_FAILED");
        }

        @Test
        @DisplayName("path variable không phải UUID (MethodArgumentTypeMismatchException): 400")
        void path_variable_sai_uuid_tra_400() throws Exception {
            assertProblemShape(perform(get("/typed/khong-phai-uuid")), 400, "VALIDATION_FAILED");
        }

        @Test
        @DisplayName("thiếu query param bắt buộc (MissingServletRequestParameterException): 400")
        void thieu_query_param_tra_400() throws Exception {
            assertProblemShape(perform(get("/required")), 400, "VALIDATION_FAILED");
        }

        @Test
        @DisplayName("thiếu header bắt buộc (MissingRequestHeaderException): 400")
        void thieu_header_tra_400() throws Exception {
            assertProblemShape(perform(get("/header")), 400, "VALIDATION_FAILED");
        }

        @Test
        @DisplayName(
                "@Size trên @RequestParam không có @Validated (HandlerMethodValidationException): 400 kèm meta.fields")
        void rang_buoc_tham_so_khong_validated_tra_400() throws Exception {
            MvcResult result = perform(get("/size").param("q", "qua-dai"));

            assertProblemShape(result, 400, "VALIDATION_FAILED");
            // Cùng hình dạng meta.fields với hai handler validation còn lại. Hiện nó rơi vào
            // handleStatusException (HandlerMethodValidationException kế thừa ResponseStatusException)
            // nên ra 400 "Request rejected" KHÔNG có fields — frontend không biết trường nào sai.
            assertThat(body(result).path("meta").path("fields").has("q")).isTrue();
        }

        @Test
        @DisplayName("sai HTTP method (HttpRequestMethodNotSupportedException): 405")
        void sai_method_tra_405() throws Exception {
            MvcResult result = perform(delete("/typed").param("count", "1"));

            assertThat(result.getResponse().getStatus()).isEqualTo(405);
        }

        @Test
        @DisplayName("sai Content-Type (HttpMediaTypeNotSupportedException): 415")
        void sai_content_type_tra_415() throws Exception {
            MvcResult result =
                    perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("x"));

            assertThat(result.getResponse().getStatus()).isEqualTo(415);
        }
    }

    record Dto(@NotBlank String name, @Min(1) int quantity) {}

    enum ServiceCode implements ErrorCode {
        HOLD_EXPIRED;

        @Override
        public String code() {
            return name();
        }

        @Override
        public int httpStatus() {
            return 422;
        }
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/api-ex/{kind}")
        String apiException(@PathVariable String kind) {
            throw switch (kind) {
                case "conflict" -> new ApiException(
                        ErrorCode.Common.CONFLICT, "Seat already held", Map.of("seatId", "A-12"));
                case "not-found" -> ApiException.notFound("Order");
                case "forbidden" -> ApiException.forbidden("Not a member");
                case "upstream" -> new ApiException(ErrorCode.Common.UPSTREAM_UNAVAILABLE, "payOS down");
                default -> new ApiException(ServiceCode.HOLD_EXPIRED, "Hold expired");
            };
        }

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@Valid @RequestBody Dto dto) {
            return dto.name();
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException(SECRET_MESSAGE);
        }

        @GetMapping("/npe")
        String npe() {
            Object secretField = null;
            return secretField.toString();
        }

        @GetMapping("/status")
        String status() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "internal state of reservation 42");
        }

        @GetMapping("/async-timeout")
        String asyncTimeout() {
            throw new AsyncRequestTimeoutException();
        }

        @GetMapping("/typed")
        String typed(@RequestParam int count) {
            return Integer.toString(count);
        }

        @GetMapping("/typed/{id}")
        String typedPath(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/required")
        String required(@RequestParam String q) {
            return q;
        }

        @GetMapping("/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @GetMapping("/size")
        String size(@RequestParam @Size(max = 3) String q) {
            return q;
        }
    }
}
