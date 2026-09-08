// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Khách tự huỷ đơn chưa thanh toán. */
@Service
public class CloseOrderHandler {

    private static final Logger log = LoggerFactory.getLogger(CloseOrderHandler.class);

    private final OrderRepository orders;
    private final OutboxPort outbox;
    private final InventoryPort inventory;
    private final Clock clock;

    public CloseOrderHandler(OrderRepository orders, OutboxPort outbox, InventoryPort inventory, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional
    public void cancel(UUID orderId, UUID userId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));

        // Đơn của người khác cũng trả 404: trả 403 là xác nhận orderId đó có thật.
        if (!order.isOwnedBy(userId)) {
            throw new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found");
        }
        if (!order.close(OrderStatus.CANCELLED, clock.instant(), "Khách huỷ")) {
            throw new ApiException(
                    OrderingErrorCode.ORDER_NOT_OPEN, "Order is no longer awaiting payment: " + order.status());
        }
        orders.updateStatus(order);
        outbox.orderClosed(order);

        // Nhả chỗ ngay thay vì chỉ dựa vào sự kiện: khách vừa huỷ thường là để chọn chỗ khác,
        // và bắt họ đợi outbox publisher chạy xong là mất một lần bán.
        releaseSeatsBestEffort(order.id());
    }

    /**
     * Nhả chỗ theo kiểu "cố gắng hết sức".
     *
     * <p>Hỏng ở đây <b>không</b> được làm rollback việc huỷ đơn: đơn đã huỷ vẫn phải là đã huỷ.
     * Sự kiện {@code OrderClosed} trong outbox là đường chắc chắn, còn lời gọi này chỉ để nhanh.
     */
    private void releaseSeatsBestEffort(UUID orderId) {
        try {
            inventory.cancelReservation(orderId);
        } catch (RuntimeException e) {
            log.warn("Không nhả được chỗ ngay cho đơn {}, chờ sự kiện OrderClosed", orderId, e);
        }
    }
}
