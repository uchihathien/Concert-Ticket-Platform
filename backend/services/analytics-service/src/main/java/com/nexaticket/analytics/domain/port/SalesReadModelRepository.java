// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.domain.port;

import com.nexaticket.analytics.domain.model.SalesDelta;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesReadModelRepository {

    /**
     * Cộng delta vào read model, <b>bỏ qua nếu sự kiện đã xử lý</b>.
     *
     * <p>Read model được xây bằng phép cộng dồn, và cộng dồn thì không tự idempotent: xử lý lại
     * một {@code order.paid} sẽ cộng doanh thu lần thứ hai. RabbitMQ giao ít nhất một lần nên
     * điều đó chắc chắn xảy ra.
     *
     * @return true nếu delta thực sự được cộng
     */
    boolean applyIfNew(UUID eventId, String eventType, SalesDelta delta);

    Optional<SessionSales> bySession(UUID eventSessionId);

    List<SessionSales> byOrganization(UUID organizationId);

    /**
     * Số liệu của mọi suất thuộc một sự kiện.
     *
     * <p>Có index riêng ({@code idx_sales_event}) chứ không lọc trong bộ nhớ từ
     * {@link #byOrganization}: một tổ chức lớn có hàng trăm suất, và màn hình master data chỉ cần
     * vài suất của một sự kiện.
     */
    List<SessionSales> byEvent(UUID eventId);

    /**
     * Những sự kiện bán chạy nhất, xếp giảm dần theo số vé đã bán.
     *
     * <p>Gộp theo sự kiện chứ không theo suất: khách xem trang chủ quan tâm "concert nào đang hot",
     * không quan tâm suất nào trong đó bán chạy. Một sự kiện bốn suất mỗi suất bán vừa phải vẫn
     * nóng hơn một sự kiện một suất bán được ngần ấy vé.
     *
     * @param limit số sự kiện lấy về, đã kẹp ở tầng gọi
     */
    List<EventPopularity> trendingEvents(int limit);

    record EventPopularity(UUID eventId, int ticketsSold) {}

    /**
     * Số liệu bán hàng của một suất diễn, <b>như tổ chức được phép thấy</b>.
     *
     * <p>Không có hoa hồng, không có số dư, không có lịch chi trả (ADR-1010).
     */
    record SessionSales(
            UUID eventSessionId,
            UUID eventId,
            UUID organizationId,
            int ticketsSold,
            long grossVnd,
            int ordersPaid,
            int ordersExpired,
            int ordersCancelled) {}
}
