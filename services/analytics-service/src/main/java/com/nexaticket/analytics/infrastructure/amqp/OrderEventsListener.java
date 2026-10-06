// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.analytics.application.command.ApplySalesDeltaHandler;
import com.nexaticket.analytics.domain.model.SalesDelta;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận mọi sự kiện của Ordering và cộng vào read model doanh thu.
 *
 * <p>Trước consumer này, {@link ApplySalesDeltaHandler} chỉ được test gọi — nên trang "Doanh thu"
 * của ban tổ chức luôn bằng 0 dù vé bán thật.
 *
 * <p>Một queue {@code "#"} cho cả bốn routing key thay vì bốn queue rời (topology.yaml): read model
 * cần cả đơn hết hạn và đơn huỷ, không chỉ đơn đã trả tiền. Và một queue duy nhất giữ cho thứ tự
 * nhận trong cùng một aggregate không bị xáo thêm bởi chính topology.
 *
 * <p><b>Không phụ thuộc thứ tự</b>: mỗi message mang một <b>delta</b>, và tổng các delta không đổi
 * dù chúng đến theo thứ tự nào. {@code order.created} bị bỏ qua — một đơn chưa trả tiền chưa đóng
 * góp gì vào doanh thu, và đếm nó rồi trừ đi khi hết hạn chỉ thêm hai lần ghi cho cùng một số 0.
 */
@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private static final String QUEUE = "analytics.ordering.all";

    private final ApplySalesDeltaHandler applyDelta;
    private final ObjectMapper json;

    public OrderEventsListener(ApplySalesDeltaHandler applyDelta, ObjectMapper json) {
        this.applyDelta = applyDelta;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE)
    @Transactional
    public void onOrderEvent(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message ordering hỏng: {}", e.getMessage());
            return;
        }

        UUID eventSessionId = event.uuid("eventSessionId");
        UUID catalogEventId = event.uuid("eventId");
        UUID organizationId = event.uuid("organizationId");
        if (eventSessionId == null || catalogEventId == null || organizationId == null) {
            // Đơn tạo trước migration V0102 của Ordering không mang `eventId`, và cột
            // `session_sales.event_id` là NOT NULL. Bỏ qua chứ không bịa: một dòng doanh thu gắn
            // sai sự kiện tệ hơn một dòng thiếu, vì không ai nhìn ra nó sai.
            log.warn("Bỏ qua {} vì thiếu eventSessionId/eventId/organizationId", event.eventType());
            return;
        }

        SalesDelta delta = deltaOf(event, eventSessionId, catalogEventId, organizationId);
        if (delta == null) {
            return;
        }
        if (applyDelta.handle(event.eventId(), event.eventType(), delta)) {
            log.debug("Đã cộng {} vào read model của suất {}", event.eventType(), eventSessionId);
        }
    }

    /** @return null với những sự kiện không đóng góp gì vào read model */
    private static SalesDelta deltaOf(
            ConsumedEvent event, UUID eventSessionId, UUID catalogEventId, UUID organizationId) {
        String type = event.eventType() == null ? "" : event.eventType();
        return switch (type) {
            case "order.paid" -> SalesDelta.orderPaid(
                    eventSessionId,
                    catalogEventId,
                    organizationId,
                    (int) event.number("ticketCount"),
                    event.number("totalVnd"));
            case "order.expired" -> SalesDelta.orderExpired(eventSessionId, catalogEventId, organizationId);
            case "order.cancelled" -> SalesDelta.orderCancelled(eventSessionId, catalogEventId, organizationId);
            case "order.refunded" -> SalesDelta.refunded(
                    eventSessionId,
                    catalogEventId,
                    organizationId,
                    (int) event.number("ticketCount"),
                    event.number("totalVnd"));
            default -> null;
        };
    }
}
