// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import java.time.Instant;
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
     * Bộ lọc của trang danh sách công khai.
     *
     * <p>Gom thành một record thay vì bảy tham số rời: {@code publishedEvents} và
     * {@code countPublishedEvents} <b>phải</b> lọc giống hệt nhau, và cách chắc chắn nhất để chúng
     * không lệch là hai hàm nhận đúng cùng một đối tượng. Bảy tham số rời thì một lần thêm điều
     * kiện sẽ được truyền vào một hàm mà quên hàm kia, và triệu chứng là tổng số trang không khớp
     * với số dòng thật — một lỗi chỉ lộ ra ở trang cuối.
     *
     * <p><b>Lọc trên giá trị dẫn xuất, không trên bảng gốc.</b> {@code from}/{@code to} so với
     * <i>suất kế tiếp</i> của sự kiện và {@code minPriceVnd}/{@code maxPriceVnd} so với <i>giá thấp
     * nhất</i> — đúng hai con số hiện trên thẻ sự kiện. Lọc theo "có suất bất kỳ trong khoảng" sẽ
     * trả về một sự kiện mà thẻ của nó hiện một ngày nằm ngoài khoảng vừa lọc, và người dùng đọc
     * đó là lỗi.
     *
     * @param query từ khoá tìm trong tiêu đề; null hoặc rỗng thì bỏ qua
     * @param city lọc theo thành phố của địa điểm; null thì bỏ qua
     * @param category lọc theo phân loại; null thì bỏ qua
     * @param from mốc sớm nhất của suất kế tiếp, lấy cả mốc này
     * @param to mốc muộn nhất, <b>không</b> lấy mốc này — khoảng nửa mở {@code [from, to)} để hai
     *     lựa chọn liền nhau ("tháng này", "tháng sau") không cùng nhận một sự kiện
     * @param maxPriceVnd cũng không lấy mốc trên, cùng lý do: "dưới 500k" và "500k–1tr" phải rời nhau
     */
    record EventFilter(
            String query, String city, String category, Instant from, Instant to, Long minPriceVnd, Long maxPriceVnd) {

        /** Không lọc gì — trang danh sách mặc định. */
        public static EventFilter none() {
            return new EventFilter(null, null, null, null, null, null, null);
        }
    }

    /** Sự kiện đang bán, cho trang công khai. */
    List<CatalogViews.EventCard> publishedEvents(EventFilter filter, int limit, int offset);

    int countPublishedEvents(EventFilter filter);

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
