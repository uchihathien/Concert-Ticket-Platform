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
        SeatMapView view = queries.seatMap(eventSessionId, viewerId);
        String etag = etagOf(view, viewerId);

        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(view);
    }

    /**
     * ETag phải phụ thuộc cả người xem, không chỉ version.
     *
     * <p>{@code purchaseAllowance} khác nhau theo từng khách. Nếu ETag chỉ là version thì hai khách
     * cùng suất sẽ dùng chung cache và người này thấy hạn mức của người kia.
     */
    private static String etagOf(SeatMapView view, UUID viewerId) {
        return "\"" + view.availabilityVersion() + "-" + (viewerId == null ? "anon" : viewerId) + "\"";
    }

    /** Khách chưa đăng nhập vẫn xem được sơ đồ, chỉ không có phần hạn mức. */
    private static UUID currentUserOrNull() {
        var scope = TenantContext.current();
        return scope.isAuthenticated() ? scope.userId().value() : null;
    }
}
