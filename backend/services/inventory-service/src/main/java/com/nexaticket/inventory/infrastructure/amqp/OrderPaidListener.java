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
 * Nhận {@code order.paid} và chuyển chỗ RESERVED → SOLD.
 *
 * <p>Bước này từng <b>không có ai gọi</b>: endpoint {@code POST /internal/reservations/{id}/settle}
 * tồn tại từ đầu nhưng không service nào gọi nó, nên mọi chỗ đã bán vẫn nằm mãi ở RESERVED. Hậu quả
 * không phải bán trùng — RESERVED cũng là "không mua được" — mà là bất biến trong state machine
 * không bao giờ đúng, và mọi thứ dựa vào SOLD về sau (hoàn vé, đối soát chỗ ngồi) đọc ra một hệ
 * thống chưa bán được vé nào.
 *
 * <p>Đi bằng message chứ không phải lời gọi HTTP từ Ordering, và đó là chủ đích: hàng đợi
 * {@code inventory.ordering.paid} đã có sẵn trong topology, xác nhận thanh toán không được phụ
 * thuộc việc Inventory có đang sống hay không, và message thì được giao lại còn một lời gọi hỏng
 * giữa webhook thì không.
 *
 * <p>Idempotent hai lớp: {@link ProcessedEvents} theo {@code messageId}, và
 * {@code updateReservationStatus} trả 0 khi bản ghi đã ở trạng thái đích.
 */
@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);
    private static final String QUEUE = "inventory.ordering.paid";

    private final SettleReservationHandler settleReservation;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper json;

    public OrderPaidListener(
            SettleReservationHandler settleReservation, ProcessedEvents processedEvents, ObjectMapper json) {
        this.settleReservation = settleReservation;
        this.processedEvents = processedEvents;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE)
    @Transactional
    public void onOrderPaid(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message order.paid hỏng: {}", e.getMessage());
            return;
        }

        if (!processedEvents.markIfNew(QUEUE, event.eventId().toString())) {
            log.debug("Sự kiện {} đã xử lý trước đó", event.eventId());
            return;
        }

        UUID orderId = event.uuid("orderId");
        if (orderId == null) {
            log.error("Message order.paid thiếu orderId, bỏ qua");
            return;
        }

        if (settleReservation.settle(orderId)) {
            log.info("Đã chốt chỗ của đơn {} sang SOLD", orderId);
        } else {
            // Đã chốt từ trước, hoặc đơn này không có đặt chỗ nào ở Inventory. Cả hai đều là
            // "không còn gì để làm" chứ không phải lỗi, và giao lại cũng không đổi được gì.
            log.debug("Đơn {} không có gì để chốt", orderId);
        }
    }
}
