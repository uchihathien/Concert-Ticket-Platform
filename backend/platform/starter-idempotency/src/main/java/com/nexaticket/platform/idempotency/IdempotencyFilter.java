// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.id.CorrelationContext;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiError;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Lưới thứ nhất chống bấm hai lần (ADR-0007): {@code POST} trên các route được khai trong
 * {@link IdempotencyProperties#requiredPaths()} bắt buộc có {@code Idempotency-Key}.
 *
 * <ul>
 *   <li><b>Thiếu / sai khuôn khoá:</b> 400 {@code IDEMPOTENCY_KEY_REQUIRED}, handler không chạy.
 *   <li><b>Lần đầu:</b> giữ chỗ trong {@code idempotency_records}, chạy handler, và nếu thành công
 *       (2xx) thì lưu nguyên câu trả lời.
 *   <li><b>Gửi lại, cùng nội dung:</b> trả đúng câu trả lời đã lưu (header {@code Idempotent-Replayed}),
 *       handler KHÔNG chạy lần hai.
 *   <li><b>Cùng khoá, nội dung khác:</b> 409 {@code IDEMPOTENCY_KEY_REUSED}.
 *   <li><b>Đang chạy ở request khác:</b> 409 {@code REQUEST_IN_PROGRESS}.
 *   <li><b>Handler lỗi (không phải 2xx):</b> bỏ chỗ đã giữ để client thử lại được. Chỉ câu trả lời
 *       thành công mới được phát lại: phát lại một 409 "hết ghế" mãi mãi cho khoá đó là sai khi ghế
 *       vừa được nhả.
 * </ul>
 *
 * <p>Khoá có phạm vi theo người dùng ({@code TenantContext}): hai người dùng trùng khoá ngẫu nhiên
 * không đọc được câu trả lời của nhau. Bộ lọc chạy SAU chuỗi bảo mật nên request chưa đăng nhập
 * không bao giờ tới đây.
 *
 * <p>Không có {@link IdempotencyStore} (service không có database) thì chỉ kiểm khoá có mặt, không
 * phát lại.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    /** In được, không khoảng trắng: khoá đi vào log và vào khoá cơ sở dữ liệu. */
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final IdempotencyProperties properties;
    private final Optional<IdempotencyStore> store;
    private final ObjectMapper json;
    private final AntPathMatcher paths = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyProperties properties, Optional<IdempotencyStore> store, ObjectMapper json) {
        this.properties = properties;
        this.store = store;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return properties.requiredPaths().stream().noneMatch(pattern -> paths.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !KEY.matcher(key).matches()) {
            reject(response, ErrorCode.Common.IDEMPOTENCY_KEY_REQUIRED, "Header Idempotency-Key là bắt buộc");
            return;
        }
        if (store.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        UUID userId;
        try {
            userId = TenantContext.requireAuthenticated().userId().value();
        } catch (ApiException e) {
            reject(response, ErrorCode.Common.UNAUTHENTICATED, "Cần đăng nhập");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(properties.maxBodyBytes() + 1);
        if (body.length > properties.maxBodyBytes()) {
            reject(response, ErrorCode.Common.VALIDATION_FAILED, "Thân request quá lớn");
            return;
        }
        String hash = fingerprint(request, body);
        IdempotencyStore db = store.get();

        if (!db.tryBegin(userId, key, hash) && !db.takeOverIfStale(userId, key, hash, properties.staleAfter())) {
            replayOrReject(response, db.find(userId, key), hash);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                db.complete(userId, key, status, jsonBody(wrapped));
                stored = true;
            }
        } finally {
            if (!stored) {
                db.release(userId, key);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replayOrReject(HttpServletResponse response, Optional<IdempotencyStore.Existing> existing, String hash)
            throws IOException {
        if (existing.isEmpty()) {
            // Bản ghi vừa được bỏ giữa hai lệnh đọc: request kia lỗi. Báo thử lại thay vì chạy song song.
            reject(response, ErrorCode.Common.REQUEST_IN_PROGRESS, "Request cùng khoá đang được xử lý, thử lại");
            return;
        }
        IdempotencyStore.Existing found = existing.get();
        if (!found.requestHash().equals(hash)) {
            reject(
                    response,
                    ErrorCode.Common.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key này đã được dùng cho một request khác");
            return;
        }
        if (!found.isComplete()) {
            reject(response, ErrorCode.Common.REQUEST_IN_PROGRESS, "Request cùng khoá đang được xử lý, thử lại");
            return;
        }
        response.setStatus(found.responseStatus());
        response.setHeader(REPLAY_HEADER, "true");
        if (found.responseBody() != null) {
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(found.responseBody());
        }
        log.debug("Phát lại câu trả lời đã lưu cho một Idempotency-Key");
    }

    private void reject(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write(json.writeValueAsString(ApiError.of(code, detail, CorrelationContext.current(), Map.of())));
    }

    /** Thân JSON để lưu vào cột jsonb; rỗng hoặc không phải JSON thì không lưu thân. */
    private static String jsonBody(ContentCachingResponseWrapper response) {
        byte[] bytes = response.getContentAsByteArray();
        String type = response.getContentType();
        if (bytes.length == 0 || type == null || !type.toLowerCase().contains("json")) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String head = request.getMethod() + "\n" + request.getRequestURI() + "\n" + request.getQueryString() + "\n";
            digest.update(head.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 luôn có trong JDK", e);
        }
    }

    /** Cho handler đọc lại thân đã bị bộ lọc đọc để băm. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Đọc đồng bộ từ bộ nhớ");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
