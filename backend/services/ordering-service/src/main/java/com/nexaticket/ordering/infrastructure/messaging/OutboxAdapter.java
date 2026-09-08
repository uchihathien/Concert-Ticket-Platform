// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.messaging;

import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.platform.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phát sự kiện của Ordering qua transactional outbox.
 *
 * <p>Payload cố ý <b>không</b> chứa danh sách ghế: consumer nào cần chi tiết thì gọi
 * {@code GET /internal/orders/{id}}. Nhét cả đơn vào message sẽ biến mọi thay đổi cấu trúc đơn
 * hàng thành một đợt phá vỡ hợp đồng với mọi consumer.
 */
@Component
public class OutboxAdapter implements OutboxPort {

    private static final String EXCHANGE = "nexaticket.ordering";

    private final OutboxWriter writer;

    public OutboxAdapter(OutboxWriter writer) {
        this.writer = writer;
    }

    @Override
    public void orderCreated(Order order) {
        writer.append(EXCHANGE, "Order", order.id(), "order.created", basePayload(order));
    }

    @Override
    public void orderPaid(Order order) {
        Map<String, Object> payload = basePayload(order);
        // Ledger cần đúng ba số này để ghi bút toán N1; gửi kèm để nó không phải gọi ngược lại
        // Ordering ngay trong lúc xử lý webhook.
        payload.put("commissionBps", order.commissionBps());
        payload.put("commissionVnd", order.commission().amountVnd());
        payload.put("paidAt", order.paidAt().toString());
        writer.append(EXCHANGE, "Order", order.id(), "order.paid", payload);
    }

    @Override
    public void orderClosed(Order order) {
        Map<String, Object> payload = basePayload(order);
        payload.put("closeReason", order.closeReason());
        // Routing key phân biệt hết hạn với huỷ tay: Inventory xử lý như nhau, nhưng analytics
        // và notification thì không — email "đơn của bạn đã hết hạn" khác hẳn khi khách tự huỷ.
        writer.append(
                EXCHANGE, "Order", order.id(), "order." + order.status().name().toLowerCase(), payload);
    }

    private static Map<String, Object> basePayload(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id().toString());
        payload.put("orderNumber", order.orderNumber().value());
        payload.put("organizationId", order.organizationId().toString());
        payload.put("eventSessionId", order.eventSessionId().toString());
        payload.put("userId", order.userId().toString());
        payload.put("totalVnd", order.total().amountVnd());
        payload.put("ticketCount", order.items().size());
        return payload;
    }
}
