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
 * Cập nhật read model từ mọi sự kiện của ordering-service.
 *
 * <p>Một queue duy nhất bind bằng routing key {@code #} thay vì ba queue rời cho paid/expired/
 * cancelled. Lý do: read model cần cả ba, và ba queue rời sẽ để chúng đến theo ba nhịp độc lập —
 * làm việc suy luận "vì sao con số này lệch" khó hơn hẳn mà không đổi lại được gì.
 *
 * <p>Sự kiện lạ (loại chưa biết) được <b>bỏ qua nhưng vẫn ack</b>. Ordering có thể thêm loại sự
 * kiện mới bất cứ lúc nào; nếu consumer nack mọi thứ nó chưa hiểu thì một lần thêm sự kiện ở
 * service khác sẽ làm nghẽn queue của service này.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final ApplySalesDeltaHandler applyDelta;
    private final ObjectMapper json;

    public OrderEventListener(ApplySalesDeltaHandler applyDelta, ObjectMapper json) {
        this.applyDelta = applyDelta;
        this.json = json;
    }

    @RabbitListener(queues = "analytics.ordering.all")
    @Transactional
    public void onOrderEvent(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message ordering hỏng: {}", e.getMessage());
            return;
        }

        SalesDelta delta = deltaOf(event);
        if (delta == null) {
            log.debug("Bỏ qua sự kiện không ảnh hưởng read model: {}", event.eventType());
            return;
        }
        applyDelta.handle(event.eventId(), event.eventType(), delta);
    }

    /**
     * Dịch sự kiện thành phần đóng góp vào read model.
     *
     * <p>Trả về <b>delta</b> chứ không phải trạng thái tuyệt đối — đó là thứ khiến consumer này
     * không phụ thuộc thứ tự (ADR-1009). {@code order.created} cố ý không đóng góp gì: đơn chỉ
     * được tính vào doanh thu khi đã trả tiền.
     *
     * @return null với sự kiện không ảnh hưởng read model
     */
    private static SalesDelta deltaOf(ConsumedEvent event) {
        UUID sessionId = event.uuid("eventSessionId");
        UUID eventId = event.uuid("eventId");
        UUID organizationId = event.uuid("organizationId");
        if (sessionId == null || organizationId == null) {
            log.warn("Sự kiện {} thiếu định danh suất diễn hoặc tổ chức, bỏ qua", event.eventType());
            return null;
        }

        return switch (event.eventType()) {
            case "order.paid" -> SalesDelta.orderPaid(
                    sessionId, eventId, organizationId, (int) event.number("ticketCount"), event.number("totalVnd"));
            case "order.expired" -> SalesDelta.orderExpired(sessionId, eventId, organizationId);
            case "order.cancelled" -> SalesDelta.orderCancelled(sessionId, eventId, organizationId);
            case "order.refunded" -> SalesDelta.refunded(
                    sessionId, eventId, organizationId, (int) event.number("ticketCount"), event.number("totalVnd"));
            default -> null;
        };
    }
}
