// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.FloorPlanQuery;
import com.nexaticket.catalog.application.query.FloorPlanViews;
import com.nexaticket.platform.web.error.ApiException;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@Validated
public class PublicCatalogController {

    /** Trần cứng: không ai cần 1.000 sự kiện một lần, nhưng có người sẽ thử. */
    private static final int MAX_PAGE_SIZE = 60;

    private final CatalogQueries queries;
    private final FloorPlanQuery floorPlans;

    public PublicCatalogController(CatalogQueries queries, FloorPlanQuery floorPlans) {
        this.queries = queries;
        this.floorPlans = floorPlans;
    }

    /**
     * Danh sách sự kiện đang bán.
     *
     * <h3>Vì sao thời gian và giá nhận dạng KHOẢNG, không nhận tên lựa chọn</h3>
     *
     * <p>Endpoint nhận {@code from}/{@code to} và {@code minPrice}/{@code maxPrice} chứ không nhận
     * {@code when=weekend} hay {@code price=under-500}. Hai lý do:
     *
     * <ul>
     *   <li>"Cuối tuần này" phụ thuộc <b>hôm nay là thứ mấy ở Việt Nam</b>. Tiến trình backend
     *       thường chạy giờ UTC, và 07:00 giờ Việt Nam là 00:00 UTC — để backend tự giải nghĩa thì
     *       "hôm nay" nhảy sang hôm khác đúng vào buổi sáng. Frontend đã có phép tính ấy và nó
     *       đúng; đưa vào đây là dựng bản sao thứ hai để lệch.
     *   <li>Danh sách lựa chọn là quyết định giao diện. Thêm mốc "3 tháng tới" đáng lẽ chỉ sửa một
     *       hằng số ở frontend, chứ không phải phát hành lại backend.
     * </ul>
     *
     * <p>Cận trên của cả hai đều <b>không lấy mốc</b>: hai lựa chọn liền nhau phải rời nhau.
     *
     * @param from mốc sớm nhất của suất kế tiếp, ISO-8601
     * @param to mốc muộn nhất, không lấy mốc này
     * @param minPrice giá thấp nhất của sự kiện, tính bằng VND
     * @param maxPrice cận trên, không lấy mốc này. Bỏ trống nghĩa là không có trần — đó là cách
     *     biểu diễn lựa chọn "trên 1.000.000đ".
     */
    @GetMapping
    public ResponseEntity<EventPage> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) @PositiveOrZero Long minPrice,
            @RequestParam(required = false) @PositiveOrZero Long maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;

        CatalogQueries.EventFilter filter =
                new CatalogQueries.EventFilter(query, city, category, from, to, minPrice, maxPrice);

        List<CatalogViews.EventCard> items = queries.publishedEvents(filter, limit, offset);
        int total = queries.countPublishedEvents(filter);

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
     * Mặt bằng khán phòng: sân khấu và đường bao từng khu.
     *
     * <p><b>Không</b> có toạ độ từng ghế ở đây. Trang chọn chỗ đã tải sơ đồ tồn kho từ inventory,
     * và mỗi ghế ở đó đã mang sẵn {@code posX}/{@code posY} cùng trạng thái còn/hết — trả thêm
     * 5.000 toạ độ ở đây là gửi lần thứ hai cùng một thứ, qua một endpoint không có ETag.
     *
     * <p>Cache 10 phút, dài hơn hẳn hai endpoint trên: sự kiện đổi theo ngày, còn hình dạng khán
     * phòng thì gần như không đổi sau khi đã bán vé — đổi nó nghĩa là rút sự kiện xuống và publish
     * lại. Đây cũng là dữ liệu giống nhau cho mọi người xem, nên {@code cachePublic} cho phép CDN
     * giữ hộ một bản.
     */
    @GetMapping("/{slug}/floor-plan")
    public ResponseEntity<FloorPlanViews.FloorPlanView> floorPlan(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
                .body(floorPlans.forPublishedEvent(slug));
    }

    /**
     * @param cities kèm luôn trong phản hồi để trang danh sách dựng bộ lọc mà không phải gọi thêm
     *     một request nữa
     */
    public record EventPage(List<CatalogViews.EventCard> items, int total, int page, int size, List<String> cities) {}
}
