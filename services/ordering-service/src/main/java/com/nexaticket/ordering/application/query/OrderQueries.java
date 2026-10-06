// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Trạng thái của nhiều đơn cùng lúc — Open Host Service.
     *
     * <p>ticketing-service gọi khi dựng danh sách vé cho ban tổ chức: vé nào thuộc đơn đã hoàn
     * tiền là thông tin ban tổ chức cần thấy, và nó chỉ có ở đây. Một lời gọi cho cả trang thay vì
     * một lời gọi cho mỗi vé.
     *
     * <p>CỐ Ý không trả gì ngoài trạng thái. Đơn hàng có hoa hồng, và ADR-1010 nói tổ chức không
     * được thấy con số đó — một endpoint trả cả đơn là một đường để nó lọt ra sau vài lần sửa vội.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> statusesOf(Collection<UUID> orderIds) {
        return orders.statusesOf(orderIds);
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
                order.items().size(),
                order.items().stream()
                        .map(item -> new InternalOrderItem(
                                item.id(),
                                item.sessionSeatId(),
                                item.seatCode(),
                                item.zoneCode(),
                                item.admissionType(),
                                item.seatLabel(),
                                item.ticketTypeName()))
                        .toList());
    }

    /**
     * Một dòng đơn, đủ để ticketing-service phát vé.
     *
     * <p>Cố ý <b>không</b> có giá và hoa hồng: vé in ra nhãn chỗ và tên hạng vé, không in giá.
     * Trả thêm trường mà consumer không cần chỉ mở rộng bề mặt hợp đồng phải giữ ổn định.
     *
     * @param orderItemId khoá chống phát hành trùng ở phía ticketing
     */
    public record InternalOrderItem(
            UUID orderItemId,
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            String ticketTypeName) {}

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
            int ticketCount,
            List<InternalOrderItem> items) {}
}
