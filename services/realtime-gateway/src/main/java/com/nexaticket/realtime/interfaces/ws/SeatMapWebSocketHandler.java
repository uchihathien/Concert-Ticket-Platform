// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.interfaces.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.realtime.application.AvailabilityUpdate;
import com.nexaticket.realtime.application.SessionRegistry;
import com.nexaticket.realtime.application.UpdateCoalescer;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Giữ kết nối WebSocket của khách đang xem sơ đồ chỗ.
 *
 * <p>Đẩy xuống client mỗi 200ms một lượt, mỗi suất diễn một message. Xem {@link UpdateCoalescer}
 * để biết vì sao không đẩy thẳng từng thay đổi.
 */
@Component
public class SeatMapWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SeatMapWebSocketHandler.class);

    private final SessionRegistry<WebSocketSession> registry;
    private final UpdateCoalescer coalescer;
    private final ObjectMapper json;

    public SeatMapWebSocketHandler(
            SessionRegistry<WebSocketSession> registry, UpdateCoalescer coalescer, ObjectMapper json) {
        this.registry = registry;
        this.coalescer = coalescer;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID eventSessionId = eventSessionOf(session);
        if (eventSessionId == null || !registry.subscribe(session, eventSessionId)) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        log.debug("Kết nối mới cho suất {} ({} kết nối)", eventSessionId, registry.connectionCount(eventSessionId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // Gỡ ở CẢ đường lỗi, không chỉ đường đóng bình thường: quên một đường là rò rỉ bộ nhớ
        // tăng dần theo số lần client mất mạng, và nó chỉ lộ ra sau nhiều ngày chạy.
        registry.remove(session);
    }

    /** Đẩy một lượt. Mỗi suất diễn đúng một message, dù vừa có bao nhiêu thay đổi. */
    @Scheduled(fixedDelayString = "${nexaticket.realtime.coalesce-window:200ms}")
    public void flush() {
        for (AvailabilityUpdate update : coalescer.drain()) {
            String payload = write(update);
            if (payload == null) {
                continue;
            }
            registry.forEachSubscriber(update.eventSessionId(), session -> send(session, payload));
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException | IllegalStateException e) {
            // Client chậm làm đầy buffer gửi. Đóng kết nối thay vì để buffer nuốt hết heap —
            // client sẽ tự nối lại và fetch lại sơ đồ, mất vài trăm mili giây chứ không mất gì.
            log.debug("Đóng kết nối chậm", e);
            close(session, CloseStatus.SESSION_NOT_RELIABLE);
            registry.remove(session);
        }
    }

    private String write(AvailabilityUpdate update) {
        try {
            return json.writeValueAsString(Map.of(
                    "type", "availability",
                    "eventSessionId", update.eventSessionId().toString(),
                    "version", update.version()));
        } catch (Exception e) {
            log.warn("Không serialize được thông báo khả dụng", e);
            return null;
        }
    }

    /** {@code /ws/sessions/{eventSessionId}} */
    private static UUID eventSessionOf(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        int index = path.lastIndexOf('/');
        try {
            return UUID.fromString(path.substring(index + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Không đóng được kết nối", e);
        }
    }
}
