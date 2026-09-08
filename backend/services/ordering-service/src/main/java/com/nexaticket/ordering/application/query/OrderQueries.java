// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đường đọc đơn hàng. Trả DTO của tầng application, không trả aggregate ra ngoài. */
@Service
public class OrderQueries {

    private final OrderRepository orders;

    public OrderQueries(OrderRepository orders) {
        this.orders = orders;
    }

    /** Chỉ chủ đơn xem được; người khác nhận 404 chứ không phải 403. */
    @Transactional(readOnly = true)
    public OrderView byIdForUser(UUID orderId, UUID userId) {
        Order order = orders.findById(orderId)
                .filter(o -> o.isOwnedBy(userId))
                .orElseThrow(() -> new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));
        return OrderView.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderView> forUser(UUID userId, int limit, int offset) {
        return orders.findByUser(userId, limit, offset).stream()
                .map(OrderView::from)
                .toList();
    }

    /** Open Host Service: Ledger và Payout tra chứng từ gốc, nên bản này CÓ hoa hồng. */
    @Transactional(readOnly = true)
    public InternalOrderView internalById(UUID orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));
        return new InternalOrderView(
                order.id(),
                order.orderNumber().value(),
                order.organizationId(),
                order.eventSessionId(),
                order.userId(),
                order.status().name(),
                order.total().amountVnd(),
                order.commissionBps(),
                order.commission().amountVnd(),
                order.paymentReference(),
                order.paidAt(),
                order.items().size());
    }

    /**
     * Bản dành cho service nội bộ.
     *
     * @param commissionVnd phần nền tảng giữ lại; ledger-service dùng số này để ghi bút toán N1
     */
    public record InternalOrderView(
            UUID id,
            String orderNumber,
            UUID organizationId,
            UUID eventSessionId,
            UUID userId,
            String status,
            long totalVnd,
            int commissionBps,
            long commissionVnd,
            String paymentReference,
            java.time.Instant paidAt,
            int ticketCount) {}
}
