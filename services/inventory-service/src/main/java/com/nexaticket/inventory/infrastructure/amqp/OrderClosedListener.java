// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.inventory.application.command.SettleReservationHandler;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận {@code order.expired} và {@code order.cancelled}, nhả chỗ về AVAILABLE.
 *
 * <p>Đây là <b>đường chắc chắn</b> mà javadoc của {@code ExpireOrdersJob} và
 * {@code CloseOrderHandler} bên Ordering vẫn luôn nhắc tới — nhưng trước đây không tồn tại. Hai
 * lời gọi HTTP "cố gắng hết sức" bên đó là tất cả những gì có, nên một lần payment/inventory chậm
 * đúng lúc là ghế nằm mãi ở RESERVED: không bán lại được, và vì {@code holder_user_id} không được
 * xoá, chính người khách đó bị tính vào trần {@code max_tickets_per_customer} vĩnh viễn cho suất
 * diễn ấy (ADR-1014 §2).
 *
 * <p>Một queue cho cả hai routing key: với Inventory thì "hết hạn" và "khách huỷ" là cùng một
 * việc. Chỗ cần phân biệt hai cái đó là notification và analytics, không phải ở đây.
 *
 * <p>Chỉ nhả chỗ của đơn <b>chưa thanh toán</b> — và điều đó do chính routing key bảo đảm: Ordering
 * không bao giờ bắn {@code order.expired}/{@code order.cancelled} cho một đơn đã PAID, vì
 * {@code Order.close} từ chối đóng nó.
 */
@Component
public class OrderClosedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderClosedListener.class);
    private static final String QUEUE = "inventory.ordering.closed";

    private final SettleReservationHandler settleReservation;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper json;

    public OrderClosedListener(
            SettleReservationHandler settleReservation, ProcessedEvents processedEvents, ObjectMapper json) {
        this.settleReservation = settleReservation;
        this.processedEvents = processedEvents;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE)
    @Transactional
    public void onOrderClosed(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message đóng đơn hỏng: {}", e.getMessage());
            return;
        }

        if (!processedEvents.markIfNew(QUEUE, event.eventId().toString())) {
            log.debug("Sự kiện {} đã xử lý trước đó", event.eventId());
            return;
        }

        UUID orderId = event.uuid("orderId");
        if (orderId == null) {
            log.error("Message {} thiếu orderId, bỏ qua", event.eventType());
            return;
        }

        if (settleReservation.cancel(orderId)) {
            log.info("Đã nhả chỗ của đơn {} ({})", orderId, event.eventType());
        } else {
            // Thường là lời gọi HTTP "cố gắng hết sức" bên Ordering đã nhả xong trước — đường nhanh
            // thắng đường chắc chắn, đúng như thiết kế.
            log.debug("Đơn {} không còn đặt chỗ nào để nhả", orderId);
        }
    }
}
