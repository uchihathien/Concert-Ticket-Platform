// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đánh dấu đơn đã nhận tiền, sau khi payment-service xác nhận webhook.
 *
 * <p>Idempotent là bắt buộc, không phải tuỳ chọn: SePay retry webhook cho tới khi nhận 2xx, nên
 * lời gọi này gần như chắc chắn sẽ đến hai lần trong đời hệ thống. Lần thứ hai không được đổi
 * {@code paidAt} — mốc đó đi vào sổ cái và vào hạn giữ tiền — và không được bắn {@code OrderPaid}
 * lần nữa, vì ticketing-service sẽ phát vé đôi.
 */
@Service
public class ConfirmPaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPaymentHandler.class);

    private final OrderRepository orders;
    private final OutboxPort outbox;
    private final Clock clock;

    public ConfirmPaymentHandler(OrderRepository orders, OutboxPort outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void handle(UUID orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));

        if (!order.markPaid(clock.instant())) {
            log.info("Đơn {} đã PAID từ trước, bỏ qua xác nhận trùng", orderId);
            return;
        }
        orders.updateStatus(order);
        outbox.orderPaid(order);
    }
}
