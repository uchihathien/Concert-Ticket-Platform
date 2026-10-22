// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * {@link GlobalExceptionHandler#handleConstraint}: vi phạm ràng buộc trên tham số method.
 *
 * <p>Dùng validator thật của Hibernate Validator để sinh {@code ConstraintViolation} — đường dẫn
 * thuộc tính ({@code auditLogs.limit}) là thứ handler cắt gọt, nên nó phải đúng như lúc chạy thật
 * chứ không phải một chuỗi tự bịa.
 */
class ConstraintViolationHandlerTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterAll
    static void dong_factory() {
        FACTORY.close();
    }

    /** Chữ ký giống một controller thật: tên method và tên tham số là chi tiết cài đặt. */
    static class AuditController {
        @SuppressWarnings("unused")
        public String auditLogs(@Max(200) int limit, @NotBlank String cursor) {
            return "";
        }
    }

    private static ConstraintViolationException violations(Object... args) throws Exception {
        Method method = AuditController.class.getMethod("auditLogs", int.class, String.class);
        Set<ConstraintViolation<AuditController>> found =
                VALIDATOR.forExecutables().validateParameters(new AuditController(), method, args);
        return new ConstraintViolationException(found);
    }

    @Test
    @DisplayName("trả 400 VALIDATION_FAILED, phẳng hoá thành {tên-tham-số: thông điệp}")
    void phang_hoa_thanh_ten_tham_so() throws Exception {
        ConstraintViolationException ex = violations(500, " ");

        ResponseEntity<ApiError> response = handler.handleConstraint(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_FAILED");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) body.meta().get("fields");
        // Cắt về đoạn cuối: tên method `auditLogs` không đi ra ngoài.
        assertThat(fields).containsOnlyKeys("limit", "cursor");
    }

    @Test
    @DisplayName("detail không lộ tên method nội bộ hay giá trị người gọi vừa gửi")
    void detail_khong_lo_ten_method_va_gia_tri() throws Exception {
        ConstraintViolationException ex = violations(987654, "ok");
        // Thông điệp mặc định của ngoại lệ chứa cả đường dẫn nội bộ — chính thứ không được lộ ra.
        assertThat(ex.getMessage()).contains("auditLogs");

        ApiError body = handler.handleConstraint(ex).getBody();

        assertThat(body).isNotNull();
        assertThat(body.detail()).isEqualTo("Request validation failed");
        assertThat(body.toString()).doesNotContain("auditLogs").doesNotContain("987654");
    }

    @Test
    @DisplayName("cùng hình dạng meta.fields với handler @Valid, để frontend chỉ đọc một kiểu")
    void cung_hinh_dang_voi_bean_validation() throws Exception {
        ApiError body = handler.handleConstraint(violations(201, "ok")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.meta()).containsOnlyKeys("fields");
        assertThat(body.meta().get("fields")).isInstanceOf(Map.class);
    }
}
