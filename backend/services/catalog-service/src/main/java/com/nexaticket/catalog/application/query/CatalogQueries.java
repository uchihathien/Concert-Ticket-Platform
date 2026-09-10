// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Đường đọc chạy thẳng bằng SQL.
 *
 * <p>Tách khỏi repository của domain có chủ đích: repository dựng lại aggregate để <b>ghi</b> và
 * phải đọc đủ mọi thứ để giữ bất biến; đường đọc chỉ lấy đúng cột màn hình cần và được join thoải
 * mái. Bắt trang danh sách công khai đi qua {@code EventRepository} sẽ dựng cả cây suất diễn và
 * hạng vé cho hai mươi sự kiện chỉ để hiển thị tiêu đề với giá thấp nhất.
 *
 * <p>Interface ở tầng application, cài đặt ở infrastructure — vẫn là cổng và adapter, chỉ là cổng
 * của đường đọc.
 *
 * <p>Màn hình chi tiết của khu vực quản trị <b>không</b> ở đây mà ở {@link AdminCatalogQuery}: nó
 * cần cả danh sách vướng mắc trước khi publish, thứ chỉ domain trả lời được.
 */
public interface CatalogQueries {

    /**
     * Sự kiện đang bán, cho trang công khai.
     *
     * @param query từ khoá tìm trong tiêu đề; null hoặc rỗng thì bỏ qua
     * @param city lọc theo thành phố của địa điểm; null thì bỏ qua
     * @param category lọc theo phân loại; null thì bỏ qua
     */
    List<CatalogViews.EventCard> publishedEvents(String query, String city, String category, int limit, int offset);

    int countPublishedEvents(String query, String city, String category);

    Optional<CatalogViews.EventDetail> publishedEventBySlug(String slug);

    /** Thành phố đang có sự kiện bán — dựng bộ lọc từ dữ liệu thật thay vì hard-code. */
    List<String> citiesWithPublishedEvents();

    /** Bảng sự kiện của khu vực quản trị, gồm cả bản nháp. */
    List<CatalogViews.AdminEventRow> organizationEvents(UUID organizationId);

    /**
     * Suất diễn này có thật không.
     *
     * <p>Cố ý KHÔNG đòi sự kiện phải đang ở trạng thái PUBLISHED. Đường gọi duy nhất là báo giá lúc
     * tạo đơn, và lúc đó khách đã giữ được chỗ — nghĩa là suất đã từng lên bán. Ban tổ chức rút sự
     * kiện xuống giữa chừng không được làm hỏng những lần checkout đang dở: chặn bán thêm là việc
     * của Inventory qua cửa sổ bán, không phải của bước báo giá.
     */
    boolean sessionExists(UUID eventSessionId);
}
