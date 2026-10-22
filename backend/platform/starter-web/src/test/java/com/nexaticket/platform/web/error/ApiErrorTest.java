// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Hợp đồng lỗi mà frontend dựa vào: {@link ApiError}, {@link ApiException} và {@link ErrorCode}.
 *
 * <p>Mã lỗi là API công khai — đổi một mã đã phát hành là làm gãy bảng dịch ở frontend mà không
 * trình biên dịch nào báo. Nên danh sách mã chung và trạng thái HTTP của chúng được ghim ở đây.
 */
class ApiErrorTest {

    @Nested
    @DisplayName("ApiError.of")
    class Of {

        @Test
        @DisplayName("type là URI theo slug, title là slug bỏ gạch, status lấy từ mã")
        void dung_type_title_status_tu_ma() {
            ApiError error = ApiError.of(ErrorCode.Common.IDEMPOTENCY_KEY_REUSED, "reused", "corr-1", Map.of());

            assertThat(error.type()).isEqualTo("https://nexaticket.vn/errors/idempotency-key-reused");
            assertThat(error.title()).isEqualTo("idempotency key reused");
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(error.detail()).isEqualTo("reused");
            assertThat(error.correlationId()).isEqualTo("corr-1");
        }

        @Test
        @DisplayName("meta null hoặc rỗng thành null để JSON bỏ hẳn trường meta")
        void meta_rong_thanh_null() {
            assertThat(ApiError.of(ErrorCode.Common.CONFLICT, "x", "c", null).meta())
                    .isNull();
            assertThat(ApiError.of(ErrorCode.Common.CONFLICT, "x", "c", Map.of())
                            .meta())
                    .isNull();
        }

        @Test
        @DisplayName("meta có dữ liệu thì giữ nguyên")
        void meta_co_du_lieu_giu_nguyen() {
            ApiError error = ApiError.of(ErrorCode.Common.RATE_LIMITED, "slow", "c", Map.of("retryAfter", 30));

            assertThat(error.meta()).containsEntry("retryAfter", 30);
        }
    }

    @Nested
    @DisplayName("ApiException")
    class ApiExceptionTests {

        @Test
        @DisplayName("notFound ghép tên đối tượng vào detail và mang mã NOT_FOUND")
        void not_found() {
            ApiException ex = ApiException.notFound("Event");

            assertThat(ex.errorCode()).isEqualTo(ErrorCode.Common.NOT_FOUND);
            assertThat(ex.getMessage()).isEqualTo("Event not found");
            assertThat(ex.meta()).isEmpty();
        }

        @Test
        @DisplayName("forbidden mang mã FORBIDDEN và giữ detail")
        void forbidden() {
            ApiException ex = ApiException.forbidden("not a member");

            assertThat(ex.errorCode()).isEqualTo(ErrorCode.Common.FORBIDDEN);
            assertThat(ex.getMessage()).isEqualTo("not a member");
        }

        @Test
        @DisplayName("meta được sao chép phòng thủ: sửa map gốc sau khi ném không đổi được lỗi")
        void meta_sao_chep_phong_thu() {
            Map<String, Object> source = new HashMap<>();
            source.put("seat", "A1");
            ApiException ex = new ApiException(ErrorCode.Common.CONFLICT, "held", source);

            source.put("seat", "B2");
            source.put("extra", true);

            assertThat(ex.meta()).containsExactly(Map.entry("seat", "A1"));
            assertThatThrownBy(() -> ex.meta().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("meta chứa giá trị null thì bị từ chối ngay lúc tạo, không đợi tới lúc ghi JSON")
        void meta_null_value_bi_tu_choi() {
            Map<String, Object> source = new HashMap<>();
            source.put("seat", null);

            assertThatThrownBy(() -> new ApiException(ErrorCode.Common.CONFLICT, "held", source))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ErrorCode.Common")
    class Codes {

        @Test
        @DisplayName("tập mã chung đã phát hành không bị xoá hay đổi tên")
        void tap_ma_da_phat_hanh() {
            Set<String> codes = Arrays.stream(ErrorCode.Common.values())
                    .map(ErrorCode::code)
                    .collect(Collectors.toSet());

            assertThat(codes)
                    .contains(
                            "VALIDATION_FAILED",
                            "IDEMPOTENCY_KEY_REQUIRED",
                            "IDEMPOTENCY_KEY_REUSED",
                            "REQUEST_IN_PROGRESS",
                            "UNAUTHENTICATED",
                            "FORBIDDEN",
                            "NOT_FOUND",
                            "CONFLICT",
                            "RATE_LIMITED",
                            "INTERNAL_ERROR",
                            "UPSTREAM_UNAVAILABLE");
        }

        @Test
        @DisplayName("mỗi mã gắn đúng trạng thái HTTP mà frontend chờ")
        void trang_thai_http_cua_tung_ma() {
            assertThat(ErrorCode.Common.VALIDATION_FAILED.httpStatus()).isEqualTo(400);
            assertThat(ErrorCode.Common.IDEMPOTENCY_KEY_REQUIRED.httpStatus()).isEqualTo(400);
            assertThat(ErrorCode.Common.IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
            assertThat(ErrorCode.Common.REQUEST_IN_PROGRESS.httpStatus()).isEqualTo(409);
            assertThat(ErrorCode.Common.UNAUTHENTICATED.httpStatus()).isEqualTo(401);
            assertThat(ErrorCode.Common.FORBIDDEN.httpStatus()).isEqualTo(403);
            assertThat(ErrorCode.Common.NOT_FOUND.httpStatus()).isEqualTo(404);
            assertThat(ErrorCode.Common.CONFLICT.httpStatus()).isEqualTo(409);
            assertThat(ErrorCode.Common.RATE_LIMITED.httpStatus()).isEqualTo(429);
            assertThat(ErrorCode.Common.INTERNAL_ERROR.httpStatus()).isEqualTo(500);
            assertThat(ErrorCode.Common.UPSTREAM_UNAVAILABLE.httpStatus()).isEqualTo(503);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.Common.class)
        @DisplayName("code() trùng tên hằng, và là mã lỗi hợp lệ (4xx/5xx)")
        void code_trung_ten_hang(ErrorCode.Common code) {
            assertThat(code.code()).isEqualTo(code.name()).matches("[A-Z_]+");
            assertThat(code.httpStatus()).isBetween(400, 599);
        }
    }
}
