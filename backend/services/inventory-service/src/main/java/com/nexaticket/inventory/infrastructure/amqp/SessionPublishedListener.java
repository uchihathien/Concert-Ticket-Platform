// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.inventory.application.command.MaterializeSessionHandler;
import com.nexaticket.inventory.domain.port.SessionMaterializer;
import com.nexaticket.inventory.infrastructure.seed.DemoOccupancy;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận {@code session.published} từ catalog-service và dựng tồn kho.
 *
 * <p>Ba tính chất bắt buộc của một consumer, và chỗ nào bảo đảm từng cái:
 *
 * <ul>
 *   <li><b>Idempotent</b> — {@link ProcessedEvents} theo {@code messageId}, cộng thêm
 *       {@code ON CONFLICT} trên {@code session_inventory} làm lớp thứ hai.
 *   <li><b>Không phụ thuộc thứ tự</b> — message này tự chứa đủ dữ liệu và không phụ thuộc bất kỳ
 *       sự kiện nào khác, nên thứ tự không có nghĩa gì với nó.
 *   <li><b>Không chết vì message rác</b> — payload hỏng thì log và <b>ack</b>, không nack: nack sẽ
 *       khiến broker giao lại mãi một message không bao giờ xử lý được và chặn cả queue phía sau.
 * </ul>
 *
 * <p>Đánh dấu đã xử lý và dựng tồn kho nằm trong <b>cùng một transaction</b>. Ghi dấu ở transaction
 * riêng rồi mới dựng sẽ để lại một suất diễn được đánh dấu "đã xử lý" mà không có chỗ nào — và
 * message sẽ không bao giờ được giao lại để sửa.
 */
@Component
public class SessionPublishedListener {

    private static final Logger log = LoggerFactory.getLogger(SessionPublishedListener.class);
    private static final String QUEUE = "inventory.catalog.session-published";

    private final MaterializeSessionHandler materialize;
    private final ProcessedEvents processedEvents;
    private final DemoOccupancy demoOccupancy;
    private final ObjectMapper json;

    public SessionPublishedListener(
            MaterializeSessionHandler materialize,
            ProcessedEvents processedEvents,
            DemoOccupancy demoOccupancy,
            ObjectMapper json) {
        this.materialize = materialize;
        this.processedEvents = processedEvents;
        this.demoOccupancy = demoOccupancy;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE)
    @Transactional
    public void onSessionPublished(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message session.published hỏng: {}", e.getMessage());
            return;
        }

        if (!processedEvents.markIfNew(QUEUE, event.eventId().toString())) {
            log.debug("Sự kiện {} đã xử lý trước đó", event.eventId());
            return;
        }

        try {
            SessionMaterializer.SessionManifest manifest = toManifest(event.payload());
            if (materialize.handle(manifest) > 0) {
                // Chỉ chạy cho suất VỪA dựng xong. Suất đã có tồn kho từ trước có thể đã bán vé
                // thật, và bán thêm một mớ chỗ nữa vào đó là làm hỏng dữ liệu chứ không phải làm
                // đẹp sơ đồ.
                demoOccupancy.applyTo(manifest.eventSessionId(), manifest.organizationId());
            }
        } catch (RuntimeException e) {
            // Ném lại để transaction rollback, kéo theo cả dấu processed_events — message sẽ
            // được giao lại. Nuốt lỗi ở đây sẽ để lại một suất diễn không có chỗ nào bán được
            // mà không ai biết.
            log.error("Dựng tồn kho cho sự kiện {} hỏng", event.eventId(), e);
            throw e;
        }
    }

    /** Dịch payload của Catalog sang ngôn ngữ của Inventory. Không kiểu nào của Catalog đi quá đây. */
    private static SessionMaterializer.SessionManifest toManifest(JsonNode payload) {
        JsonNode limits = payload.path("limits");
        return new SessionMaterializer.SessionManifest(
                UUID.fromString(payload.get("eventSessionId").asText()),
                UUID.fromString(payload.get("eventId").asText()),
                UUID.fromString(payload.get("organizationId").asText()),
                Instant.parse(payload.get("salesOpenAt").asText()),
                Instant.parse(payload.get("salesCloseAt").asText()),
                limits.get("maxSeatedPerHold").asInt(),
                limits.get("maxStandingPerHold").asInt(),
                limits.get("maxUnitsPerHold").asInt(),
                limits.get("maxTicketsPerCustomer").asInt(),
                seatsOf(payload.path("seats")),
                standingOf(payload.path("standingBlocks")));
    }

    private static List<SessionMaterializer.SeatLine> seatsOf(JsonNode array) {
        List<SessionMaterializer.SeatLine> seats = new ArrayList<>();
        for (JsonNode node : array) {
            seats.add(new SessionMaterializer.SeatLine(
                    node.path("seatCode").asText(),
                    node.path("zoneCode").asText(),
                    text(node, "sectionLabel"),
                    text(node, "rowLabel"),
                    text(node, "seatLabel"),
                    decimal(node, "posX"),
                    decimal(node, "posY"),
                    UUID.fromString(node.path("ticketTypeId").asText()),
                    node.path("ticketTypeName").asText(),
                    node.path("priceVnd").asLong(),
                    node.path("blocked").asBoolean(false)));
        }
        return seats;
    }

    private static List<SessionMaterializer.StandingBlock> standingOf(JsonNode array) {
        List<SessionMaterializer.StandingBlock> blocks = new ArrayList<>();
        for (JsonNode node : array) {
            blocks.add(new SessionMaterializer.StandingBlock(
                    node.path("zoneCode").asText(),
                    node.path("quantity").asInt(),
                    UUID.fromString(node.path("ticketTypeId").asText()),
                    node.path("ticketTypeName").asText(),
                    node.path("priceVnd").asLong()));
        }
        return blocks;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }
}
