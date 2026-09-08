// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog công khai — không cần đăng nhập.
 *
 * <p>{@code /v1/events/**} đã được mở trong {@code SecurityAutoConfiguration}. Cả hai endpoint đều
 * chỉ đọc sự kiện {@code PUBLISHED}, và điều kiện đó nằm trong SQL chứ không ở đây.
 *
 * <p>Đặt {@code Cache-Control} vài phút: catalog đổi theo ngày chứ không theo giây, và đây là trang
 * chịu tải cao nhất lúc mở bán — mọi người vào xem trước khi bấm mua. Cố ý <b>không</b> dùng ETag
 * như sơ đồ chỗ: ở đây không có số phiên bản nào rẻ để tính, còn nội dung thì cũ vài phút không sao.
 */
@RestController
@RequestMapping("/v1/events")
public class PublicCatalogController {

    /** Trần cứng: không ai cần 1.000 sự kiện một lần, nhưng có người sẽ thử. */
    private static final int MAX_PAGE_SIZE = 60;

    private final CatalogQueries queries;

    public PublicCatalogController(CatalogQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    public ResponseEntity<EventPage> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;

        List<CatalogViews.EventCard> items = queries.publishedEvents(query, city, category, limit, offset);
        int total = queries.countPublishedEvents(query, city, category);

        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(2)).cachePublic())
                .body(new EventPage(items, total, Math.max(page, 0), limit, queries.citiesWithPublishedEvents()));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<CatalogViews.EventDetail> detail(@PathVariable String slug) {
        CatalogViews.EventDetail detail = queries.publishedEventBySlug(slug)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(2)).cachePublic())
                .body(detail);
    }

    /**
     * @param cities kèm luôn trong phản hồi để trang danh sách dựng bộ lọc mà không phải gọi thêm
     *     một request nữa
     */
    public record EventPage(List<CatalogViews.EventCard> items, int total, int page, int size, List<String> cities) {}
}
