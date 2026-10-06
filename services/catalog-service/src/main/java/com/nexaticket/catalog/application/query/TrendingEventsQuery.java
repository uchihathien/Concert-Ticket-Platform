// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.domain.port.SalesReportPort;
import com.nexaticket.catalog.domain.port.UpstreamUnavailableException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Đang hot": sự kiện bán được nhiều vé nhất.
 *
 * <h2>Vì sao xếp hạng đến từ analytics chứ không tính ở đây</h2>
 *
 * <p>Catalog không biết gì về việc bán vé — nó giữ sự kiện, suất diễn và bảng giá. Số vé đã bán
 * nằm trong read model của analytics, vốn đã tiêu thụ <b>mọi</b> sự kiện đơn hàng
 * ({@code analytics.ordering.all}) từ trước. Dựng thêm một bộ đếm thứ hai ở đây nghĩa là hai con
 * số cho cùng một câu hỏi, và ngày chúng lệch nhau thì không ai biết tin cái nào.
 *
 * <h2>Analytics chết thì trang chủ vẫn phải mở</h2>
 *
 * <p>Đây là đường công khai đông người xem nhất. Một service phụ trợ hỏng không được phép làm nó
 * trả lỗi — nên hỏng thì trả danh sách rỗng, và nơi gọi rơi về những hàng thẻ sẵn có. Khách mất
 * một hàng "đang hot"; họ không mất cả trang chủ.
 *
 * <p>Ghi log ở mức WARN chứ không nuốt im lặng: một hàng biến mất khỏi trang chủ là thứ không ai
 * nhận ra qua màn hình, nên dấu vết duy nhất phải nằm trong log.
 *
 * <h2>Số vé không đi ra ngoài</h2>
 *
 * <p>Cổng chỉ nhận về thứ tự. Số vé một sự kiện bán được là con số kinh doanh của ban tổ chức ấy,
 * và một endpoint công khai không phải chỗ công bố nó cho đối thủ của họ.
 */
@Service
public class TrendingEventsQuery {

    private static final Logger log = LoggerFactory.getLogger(TrendingEventsQuery.class);

    private final SalesReportPort sales;
    private final CatalogQueries catalog;

    public TrendingEventsQuery(SalesReportPort sales, CatalogQueries catalog) {
        this.sales = sales;
        this.catalog = catalog;
    }

    public List<CatalogViews.EventCard> handle(int limit) {
        List<UUID> ranked;
        try {
            ranked = sales.trendingEventIds(limit);
        } catch (UpstreamUnavailableException e) {
            log.warn("Không lấy được bảng xếp hạng bán chạy, trang chủ bỏ hàng \"đang hot\": {}", e.getMessage());
            return List.of();
        }

        // Thứ tự của `ranked` được giữ nguyên qua `publishedEventsByIds` — xem javadoc ở đó.
        // Sự kiện đã gỡ khỏi trang công khai rụng khỏi kết quả, nên danh sách trả về có thể ngắn
        // hơn `limit`. Đó là hành vi đúng: lấp chỗ trống bằng sự kiện bán chậm sẽ làm hàng "đang
        // hot" nói sai về chính nó.
        return catalog.publishedEventsByIds(ranked);
    }
}
