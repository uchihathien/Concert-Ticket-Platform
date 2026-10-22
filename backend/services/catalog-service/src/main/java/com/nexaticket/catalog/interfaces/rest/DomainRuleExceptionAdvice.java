// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.kernel.id.CorrelationContext;
import com.nexaticket.platform.web.error.ApiError;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Luật nghiệp vụ của domain Catalog vi phạm → 400, không phải 500.
 *
 * <p>Value object và aggregate của Catalog (khu, suất, hạng vé, khung sơ đồ, slug) từ chối dữ liệu sai
 * bằng {@link IllegalArgumentException} kèm câu giải thích nghiệp vụ. Request chỉ kiểm hình dạng
 * (@NotNull, @Positive), nên mọi luật sâu hơn đi thẳng tới đó; nếu không có advice này, người dùng gõ
 * sai mã khu nhận "Unexpected error" 500 và log ERROR kèm stack trace như một sự cố thật.
 *
 * <p>Phạm vi hẹp có chủ ý: chỉ trong service này, vì chỉ ở đây ta đã xác nhận IAE là ngoại lệ nhập
 * liệu. {@code HIGHEST_PRECEDENCE} để thắng handler {@code Exception} của starter-web.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainRuleExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handle(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED, ex.getMessage(), CorrelationContext.current(), Map.of()));
    }
}
