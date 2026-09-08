// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import com.nexaticket.ticketing.application.command.IssueTicketsForOrderHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận {@code order.paid} và phát vé.
 *
 * <p>Chống phát hành trùng ở <b>ba lớp</b>, và cả ba đều cần thiết vì hậu quả là hai người cùng
 * đến cửa với vé hợp lệ:
 *
 * <ol>
 *   <li>{@link ProcessedEvents} theo {@code messageId} — chặn message trùng ở cửa vào.
 *   <li>{@code tickets.order_item_id UNIQUE} — chặn cả khi lớp trên bị bỏ qua, ví dụ ai đó gọi
 *       thẳng endpoint nội bộ.
 *   <li>Kiểm lại trạng thái đơn ở Ordering — chặn message đến trễ sau khi đơn đã hoàn tiền.
 * </ol>
 *
 * <p>Không phụ thuộc thứ tự: mọi dữ liệu cần thiết được đọc từ Ordering tại thời điểm xử lý, nên
 * {@code order.paid} đến trước hay sau các sự kiện khác đều không đổi kết quả.
 */
@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);
    private static final String QUEUE = "ticketing.ordering.paid";

    private final IssueTicketsForOrderHandler issueTickets;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper json;

    public OrderPaidListener(
            IssueTicketsForOrderHandler issueTickets, ProcessedEvents processedEvents, ObjectMapper json) {
        this.issueTickets = issueTickets;
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

        var orderId = event.uuid("orderId");
        if (orderId == null) {
            log.error("Message order.paid thiếu orderId, bỏ qua");
            return;
        }

        // Ném ra ngoài thì transaction rollback kéo theo dấu processed_events, và message được
        // giao lại. Đó là hành vi đúng khi Ordering tạm thời không đọc được.
        int issued = issueTickets.handle(orderId);
        log.info("Đã phát {} vé cho đơn {}", issued, orderId);
    }
}
