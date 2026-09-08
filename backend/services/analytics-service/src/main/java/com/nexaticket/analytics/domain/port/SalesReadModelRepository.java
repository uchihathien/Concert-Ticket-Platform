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
