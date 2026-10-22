// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import com.nexaticket.inventory.application.query.SeatMapView;
import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sơ đồ chỗ của một suất diễn.
 *
 * <p>Đây là endpoint bị gọi nhiều nhất hệ thống — mọi khách vào trang chọn chỗ đều gọi, và gọi lại
 * mỗi lần version nhảy. Với suất 3.000 ghế payload cỡ 400KB, nên hai thứ là bắt buộc:
 *
 * <ul>
 *   <li><b>ETag</b> theo {@code availabilityVersion}: phần lớn lần gọi kết thúc bằng {@code 304}
 *       với body rỗng, kể cả khi client vừa nhận thông báo có thay đổi ở suất khác.
 *   <li><b>Nén</b> (bật ở {@code server.compression}): giữ tên field dài cho dễ debug, và để nén
 *       xoá gần hết chênh lệch so với tên field rút gọn.
 * </ul>
 *
 * <p>{@code Cache-Control: no-cache} chứ không phải {@code no-store}: client được lưu bản sao nhưng
 * <b>phải</b> hỏi lại trước khi dùng. Tồn kho ghế cũ vài giây là bán trùng.
 */
@RestController
@RequestMapping("/v1/sessions")
public class SeatMapController {

    private final SeatQueries queries;

    public SeatMapController(SeatQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/{eventSessionId}/seats")
    public ResponseEntity<SeatMapView> seats(
            @PathVariable UUID eventSessionId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        UUID viewerId = currentUserOrNull();

        // 304 SỚM: chỉ cần version (một câu truy vấn nhẹ). Bản cũ dựng cả sơ đồ — 16.796 ghế, 5,3 MB JSON — rồi
        // mới so ETag để vứt nó đi, nên request nào cũng tốn đủ bộ nhớ và CPU kể cả khi trả 304.
        String early = etagOf(queries.availabilityVersion(eventSessionId), viewerId);
        if (matches(ifNoneMatch, early)) {
            return ResponseEntity.status(304).eTag(early).build();
        }

        SeatMapView view = queries.seatMap(eventSessionId, viewerId);
        return ResponseEntity.ok()
                .eTag(etagOf(view.availabilityVersion(), viewerId))
                .cacheControl(CacheControl.noCache())
                .body(view);
    }

    /**
     * ETag phải phụ thuộc cả người xem, không chỉ version.
     *
     * <p>{@code purchaseAllowance} khác nhau theo từng khách. Nếu ETag chỉ là version thì hai khách
     * cùng suất sẽ dùng chung cache và người này thấy hạn mức của người kia.
     */
    /**
     * ETag YẾU ({@code W/"…"}), và đây là điều bắt buộc, không phải chuyện hình thức.
     *
     * <p>Tomcat KHÔNG nén một phản hồi có ETag mạnh (mặc định {@code noCompressionStrongETag=true}: nén đổi
     * từng byte của biểu diễn nên ETag mạnh sẽ sai). Bản trước dùng ETag mạnh, nên {@code server.compression}
     * trong application.yml không bao giờ có tác dụng: sơ đồ 16.796 ghế đi qua mạng nguyên 5,3 MB dù client gửi
     * {@code Accept-Encoding: gzip}. ETag yếu nói đúng điều ta muốn — "cùng nội dung ngữ nghĩa" — và cho phép nén.
     */
    private static String etagOf(long version, UUID viewerId) {
        return "W/\"" + version + "-" + (viewerId == null ? "anon" : viewerId) + "\"";
    }

    /**
     * So sánh yếu theo RFC 9110 §13.1.2: bỏ tiền tố {@code W/} ở cả hai phía, chấp nhận danh sách và {@code *}.
     * Client cũ còn giữ ETag mạnh {@code "17-anon"} của bản trước vẫn khớp, nên không phải tải lại một lần vô ích.
     */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String target = opaque(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if ("*".equals(c) || opaque(c).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String opaque(String tag) {
        String t = tag.trim();
        return t.startsWith("W/") ? t.substring(2) : t;
    }

    /** Khách chưa đăng nhập vẫn xem được sơ đồ, chỉ không có phần hạn mức. */
    private static UUID currentUserOrNull() {
        var scope = TenantContext.current();
        return scope.isAuthenticated() ? scope.userId().value() : null;
    }
}
