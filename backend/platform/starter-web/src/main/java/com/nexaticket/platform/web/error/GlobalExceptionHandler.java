// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import com.nexaticket.kernel.id.CorrelationContext;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
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

    /**
     * Vi phạm ràng buộc trên tham số của method (@code @RequestParam}, {@code @PathVariable}).
     *
     * <p>Phẳng hoá thành {@code {trường: thông điệp}} giống {@link #handleBeanValidation} ngay bên
     * trên, thay vì trả {@code ex.getMessage()}.
     *
     * <p>Thông điệp mặc định của {@code ConstraintViolationException} ghép cả <b>đường dẫn thuộc
     * tính nội bộ</b> lẫn <b>giá trị người dùng vừa gửi</b> — ví dụ
     * {@code auditLogs.limit: must be less than or equal to 200}. Tên method và tên tham số là chi
     * tiết cài đặt; lộ chúng ra không giúp gì cho người gọi mà lại vẽ sẵn bản đồ bề mặt API cho
     * người dò. Hai bộ xử lý validation trả về hai hình dạng khác nhau cũng là thứ frontend phải
     * viết hai nhánh để đọc.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            // Chỉ giữ đoạn cuối của đường dẫn thuộc tính: `auditLogs.limit` -> `limit`.
            String path = violation.getPropertyPath().toString();
            String field = path.substring(path.lastIndexOf('.') + 1);
            fields.putIfAbsent(field, violation.getMessage());
        });
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED,
                        "Request validation failed",
                        CorrelationContext.current(),
                        Map.of("fields", fields)));
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

    /**
     * Ngoại lệ do chính Spring ném ra, đã mang sẵn mã trạng thái.
     *
     * <h3>Vì sao PHẢI có handler này BÊN CẠNH {@link #handleNotFound}</h3>
     *
     * <p>Spring có <b>hai lớp {@code NoResourceFoundException} trùng tên</b>, và chúng có <b>hai cây
     * kế thừa khác nhau</b>:
     *
     * <ul>
     *   <li>{@code web.servlet.resource} (MVC) — {@code extends ServletException implements
     *       ErrorResponse}. Chỉ bắt được bằng chính lớp ấy, nên nó ở lại {@link #handleNotFound}.
     *   <li>{@code web.reactive.resource} (WebFlux) — {@code extends ResponseStatusException}, và
     *       đó là thứ handler này bắt.
     * </ul>
     *
     * <p>Bản trước chỉ import lớp của MVC, nên ở api-gateway — chạy reactive — nó không khớp: mọi
     * đường dẫn không tồn tại rơi xuống {@link #handleUnexpected} và trả về <b>500 kèm stack trace
     * ghi ở mức ERROR</b>. Client nhận sai mã, và tệ hơn: một người dò đường dẫn bơm được log ERROR
     * không giới hạn vào đúng chỗ người vận hành tìm lỗi thật.
     *
     * <p>Không gộp được hai trường hợp vào một handler theo {@code ResponseStatusException}: lớp của
     * MVC không kế thừa nó. Thử gộp là làm hỏng đường 404 của mọi service MVC — im lặng, vì không
     * có gì trong trình biên dịch nói ra điều đó.
     *
     * <h3>Mức log theo mã trạng thái</h3>
     *
     * <p>4xx là lỗi của người gọi — ghi DEBUG, vì nó xảy ra hàng ngày và không ai phải làm gì.
     * 5xx do Spring ném ra thì vẫn là chuyện của ta, nên giữ WARN kèm ngoại lệ.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatusException(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String correlationId = CorrelationContext.current();

        if (status.is4xxClientError()) {
            log.debug("{} cho {} [{}]", status.value(), ex.getReason(), correlationId);
        } else {
            log.warn("Spring trả {} [{}]", status.value(), correlationId, ex);
        }

        ErrorCode code = status.value() == 404
                ? ErrorCode.Common.NOT_FOUND
                : status.is4xxClientError() ? ErrorCode.Common.VALIDATION_FAILED : ErrorCode.Common.INTERNAL_ERROR;

        // KHÔNG dùng `ex.getReason()` làm thông điệp: với WebFlux nó là "No static resource
        // internal/reservations", một câu nói ra cấu trúc đường dẫn nội bộ cho bất kỳ ai gõ thử.
        return ResponseEntity.status(status)
                .body(ApiError.of(code, messageFor(status), correlationId, Map.of())
                        .withStatus(status.value()));
    }

    private static String messageFor(HttpStatusCode status) {
        if (status.value() == 404) {
            return "No handler for this path";
        }
        return status.is4xxClientError() ? "Request rejected" : "Unexpected error";
    }

    /**
     * Request sai ở tầng HTTP mà Spring đã phân loại sẵn: sai method (405), sai content-type (415),
     * không có media type phù hợp (406), thiếu tham số bắt buộc (400).
     *
     * <p>Không có handler này, tất cả rơi xuống {@link #handleUnexpected}: client nhận 500 cho lỗi của
     * chính họ, và mỗi lần dò đường để lại một dòng ERROR kèm stack trace trong log — chỗ người vận
     * hành tìm lỗi thật. Mã trạng thái lấy từ chính ngoại lệ ({@link ErrorResponse}); thông điệp thì
     * cố định, không trả lại chi tiết của Spring.
     */
    @ExceptionHandler({
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class,
        HttpMediaTypeNotAcceptableException.class,
        MissingServletRequestParameterException.class,
        ServletRequestBindingException.class
    })
    public ResponseEntity<ApiError> handleRejectedRequest(Exception ex) {
        HttpStatusCode status = ((ErrorResponse) ex).getStatusCode();
        log.debug("{} cho request [{}]: {}", status.value(), CorrelationContext.current(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(ApiError.of(
                                ErrorCode.Common.VALIDATION_FAILED,
                                "Request rejected",
                                CorrelationContext.current(),
                                Map.of())
                        .withStatus(status.value()));
    }

    /**
     * {@code @Size}/{@code @Min}… trực tiếp trên tham số controller không có {@code @Validated} ở
     * lớp — Spring 6.1+ ném {@link HandlerMethodValidationException} (một {@code ResponseStatusException}).
     * Trả cùng hình dạng {@code meta.fields} như hai handler validation kia, để frontend biết trường nào sai.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            String message = result.getResolvableErrors().isEmpty()
                    ? "invalid value"
                    : result.getResolvableErrors().get(0).getDefaultMessage();
            fields.putIfAbsent(
                    name == null ? "arg" + result.getMethodParameter().getParameterIndex() : name, message);
        });
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED,
                        "Request validation failed",
                        CorrelationContext.current(),
                        Map.of("fields", fields)));
    }

    /** Tham số đường dẫn / query sai kiểu — {@code GET /v1/orders/khong-phai-uuid}. Lỗi của client, không phải 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ErrorCode.Common.VALIDATION_FAILED,
                        "Invalid value for parameter '" + ex.getName() + "'",
                        CorrelationContext.current(),
                        Map.of("fields", Map.of(ex.getName(), "invalid value"))));
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

    /**
     * Request async chạy quá hạn — <b>không phải</b> lỗi lập trình.
     *
     * <p>Không có handler này thì nó rơi vào {@link #handleUnexpected} và thành 500 "Unexpected
     * error". Đã xảy ra thật ở ai-chatbox: endpoint chat trả {@code CompletableFuture} để nhả luồng
     * Tomcat, Tomcat áp hạn async mặc định 30 giây, và mọi câu trả lời của mô hình chạy tại chỗ —
     * vốn mất hàng chục giây — về tới khách dưới dạng 500 kèm câu "hãy nêu correlation id khi liên
     * hệ hỗ trợ". Người đọc đi tìm một lỗi không tồn tại.
     *
     * <p>503 chứ không 504: hết hạn ở đây nghĩa là <b>phía ta</b> chưa xử lý xong trong ngân sách đã
     * khai, không phải một máy chủ ngược dòng nào im lặng. Với client thì cả hai đều là "thử lại",
     * nhưng người vận hành cần phân biệt để biết nên nới hạn hay đi tìm service khác.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ApiError> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        log.warn("Request async quá hạn [{}]", CorrelationContext.current());
        return ResponseEntity.status(503)
                .body(ApiError.of(
                        ErrorCode.Common.UPSTREAM_UNAVAILABLE,
                        "Request took longer than the configured budget. Retry.",
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
