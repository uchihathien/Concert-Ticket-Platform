// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.correlation;

import com.nexaticket.kernel.id.CorrelationContext;
import com.nexaticket.kernel.id.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Nhận lại hoặc sinh {@code X-Correlation-Id} ở biên HTTP, đặt vào {@link CorrelationContext} và
 * MDC.
 *
 * <p>Một sự cố phải tra được từ webhook payOS tới email gửi cho khách bằng một mã duy nhất, xuyên
 * qua HTTP nội bộ và AMQP header.
 *
 * <p>Chỗ <b>giữ</b> mã nằm ở shared-kernel, không nằm trong lớp này — outbox và audit cũng cần đọc
 * nó mà không được phép phụ thuộc Spring Web.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final java.util.regex.Pattern SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * @deprecated dùng {@link CorrelationContext#current()} — nó không kéo theo Spring Web.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public static String current() {
        return CorrelationContext.current();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        // Chỉ nhận mã "trông như mã": giá trị có CR/LF đi thẳng vào MDC và vào header phản hồi sẽ giả
        // được một dòng log hoàn chỉnh (CWE-117) — chứng cứ giả đúng chỗ người vận hành tra sự cố.
        String correlationId = (incoming == null || !SAFE.matcher(incoming).matches())
                ? CorrelationId.generate().value()
                : incoming;

        CorrelationContext.set(correlationId);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            CorrelationContext.clear();
        }
    }
}
