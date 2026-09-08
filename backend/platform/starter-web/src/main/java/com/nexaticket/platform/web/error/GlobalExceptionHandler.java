// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import com.nexaticket.kernel.id.CorrelationContext;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        ApiError body = ApiError.of(ex.errorCode(), ex.getMessage(), CorrelationContext.current(), ex.meta());
        if (ex.errorCode().httpStatus() >= 500) {
            log.error("Lỗi nghiệp vụ {}: {}", ex.errorCode().code(), ex.getMessage(), ex);
        } else {
            log.info("Từ chối {}: {}", ex.errorCode().code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.errorCode().httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED,
                        "Request validation failed",
                        CorrelationContext.current(),
                        Map.of("fields", fields)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED, ex.getMessage(), CorrelationContext.current(), Map.of()));
    }

    /**
     * Lưới cuối. Không bao giờ trả thông điệp gốc ra ngoài — nó có thể chứa dữ liệu nhạy cảm hoặc chi
     * tiết hạ tầng. Người dùng nhận correlationId để báo hỗ trợ.
     */
    /**
     * Đường dẫn không tồn tại, và thân request không đọc được.
     *
     * <p>Không có hai nhánh này thì {@code handleUnexpected} nuốt chúng và trả <b>500</b> — sai với
     * cả hai. Đường dẫn gõ nhầm phải là 404, JSON hỏng phải là 400; báo 500 nghĩa là "lỗi của
     * server", và nó gửi người đi tìm sai chỗ.
     *
     * <p>Điều này từng che một lỗi thật: api-gateway gửi nhầm {@code /v1/me/orders} sang
     * identity-service, identity trả 500 vì không có handler nào, và triệu chứng trông như
     * ordering-service hỏng. Một cái 404 ở đây đã chỉ thẳng ra rằng request tới nhầm service.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception ex) {
        return ResponseEntity.status(404)
                .body(ApiError.of(
                        ErrorCode.Common.NOT_FOUND,
                        "No handler for this path",
                        CorrelationContext.current(),
                        Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(400)
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED,
                        "Malformed request body",
                        CorrelationContext.current(),
                        Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String correlationId = CorrelationContext.current();
        log.error("Lỗi không lường trước [{}]", correlationId, ex);
        return ResponseEntity.status(500)
                .body(ApiError.of(
                        ErrorCode.Common.INTERNAL_ERROR,
                        "Unexpected error. Quote the correlation id when contacting support.",
                        correlationId,
                        Map.of()));
    }
}
