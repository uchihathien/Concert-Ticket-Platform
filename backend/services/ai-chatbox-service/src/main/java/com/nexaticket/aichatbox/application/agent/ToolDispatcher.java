// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Chạy tool mà mô hình yêu cầu và biến kết quả thành thứ mô hình đọc được.
 *
 * <h2>Ba loại kết cục, ba cách trả lời khác nhau</h2>
 *
 * Đây là toàn bộ giá trị của lớp này, và nhầm lẫn giữa chúng là cách agent nói dối khách hàng:
 *
 * <ul>
 *   <li><b>Có dữ liệu</b> — JSON của dữ liệu.
 *   <li><b>Không có dữ liệu</b> (đơn không tồn tại, hoặc không phải của khách) — vẫn là kết quả
 *       <i>thành công</i>, với {@code found: false}. Đánh dấu lỗi ở đây thì mô hình nói "hệ thống
 *       đang bận" trong khi sự thật là không có đơn nào như vậy.
 *   <li><b>Không tra được</b> (quá hạn, 5xx) — kết quả <i>lỗi</i>. Trả về {@code found: false} ở
 *       đây tệ hơn nhiều: khách nghe "không tìm thấy đơn của bạn" và tin rằng đơn đã biến mất.
 * </ul>
 *
 * <p>Ngoại lệ <b>không</b> được ném ra khỏi lớp này. Một tool hỏng không làm hỏng cả lượt chat —
 * mô hình cần biết là hỏng để nói cho khách, và mọi lời gọi trong một lượt đều phải có kết quả trả
 * về, nếu không request kế tiếp sai định dạng.
 */
@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final OrderingClientPort ordering;
    private final VectorStorePort knowledge;
    private final CallerCredentialsPort credentials;
    private final ObjectMapper json;
    private final AgentMetrics metrics;

    public ToolDispatcher(
            OrderingClientPort ordering,
            VectorStorePort knowledge,
            CallerCredentialsPort credentials,
            ObjectMapper json,
            AgentMetrics metrics) {
        this.ordering = ordering;
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.json = json;
        this.metrics = metrics;
    }

    public ToolOutcome dispatch(ToolInvocation call) {
        ToolOutcome outcome = run(call);
        metrics.recordTool(call.toolName(), outcome.failed() ? "failed" : "ok");
        return outcome;
    }

    private ToolOutcome run(ToolInvocation call) {
        try {
            return switch (call.toolName()) {
                case SupportAgentTools.GET_ORDER_STATUS -> getOrderStatus(call);
                case SupportAgentTools.GET_EVENT_RULES -> getEventRules(call);
                    // Mô hình gọi một tool không tồn tại. Hiếm, nhưng xảy ra — và im lặng ở đây
                    // biến nó thành một request sai định dạng ở vòng sau.
                default -> ToolOutcome.error(call.callId(), "Tool không tồn tại: " + call.toolName());
            };
        } catch (RuntimeException e) {
            // Lưới cuối. Bất cứ thứ gì lọt tới đây là lỗi lập trình, nhưng nó vẫn không được phép
            // làm hỏng lượt chat của khách.
            log.error("Tool {} ném ngoại lệ không lường trước", call.toolName(), e);
            return ToolOutcome.error(call.callId(), "Hệ thống tra cứu gặp sự cố. Hãy nói với khách là thử lại sau.");
        }
    }

    private ToolOutcome getOrderStatus(ToolInvocation call) {
        UUID orderId = parseUuid(call.stringArg("orderId"));
        if (orderId == null) {
            // Không phải lỗi hệ thống: mô hình đưa sai tham số, và nó tự sửa được nếu ta nói rõ.
            return ToolOutcome.ok(
                    call.callId(),
                    write(Map.of(
                            "found",
                            false,
                            "reason",
                            "MÃ ĐƠN KHÔNG HỢP LỆ",
                            "hint",
                            "Hãy hỏi khách mã đơn hàng dạng UUID.")));
        }
        try {
            OrderSummary order = ordering.fetchOrder(orderId, credentials.currentAccessToken());
            return ToolOutcome.ok(call.callId(), write(describe(order)));
        } catch (OrderNotFoundException e) {
            return ToolOutcome.ok(
                    call.callId(),
                    write(Map.of("found", false, "reason", "KHÔNG CÓ ĐƠN NÀY TRONG TÀI KHOẢN CỦA KHÁCH")));
        } catch (RemoteCallException e) {
            log.warn("Không tra được đơn {}: {}", orderId, e.getMessage());
            return ToolOutcome.error(
                    call.callId(),
                    "Hệ thống đơn hàng tạm thời không phản hồi. ĐỪNG nói là không tìm thấy đơn — "
                            + "hãy mời khách thử lại sau ít phút.");
        }
    }

    private ToolOutcome getEventRules(ToolInvocation call) {
        UUID eventId = parseUuid(call.stringArg("eventId"));
        if (eventId == null) {
            return ToolOutcome.ok(call.callId(), write(Map.of("found", false, "reason", "MÃ SỰ KIỆN KHÔNG HỢP LỆ")));
        }
        Optional<EventRules> rules = knowledge.findRules(eventId);
        return rules.map(r -> ToolOutcome.ok(
                        call.callId(),
                        write(Map.of("found", true, "eventTitle", r.eventTitle(), "rules", r.content()))))
                .orElseGet(() -> ToolOutcome.ok(
                        call.callId(),
                        write(Map.of("found", false, "reason", "CHƯA CÓ QUY ĐỊNH ĐƯỢC CÔNG BỐ CHO SỰ KIỆN NÀY"))));
    }

    /**
     * Chỉ những trường khách được thấy, tên tiếng Anh và phẳng.
     *
     * <p>{@code LinkedHashMap} chứ không phải {@code Map.of}: thứ tự khoá ổn định giữa các request
     * là điều kiện để phần đầu prompt còn cache được, và {@code Map.of} không hứa thứ tự nào cả.
     */
    private static Map<String, Object> describe(OrderSummary order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("orderNumber", order.orderNumber());
        out.put("status", order.status());
        out.put("totalVnd", order.totalVnd());
        if (order.paymentExpiresAt() != null) {
            out.put("paymentExpiresAt", String.valueOf(order.paymentExpiresAt()));
        }
        if (order.paidAt() != null) {
            out.put("paidAt", String.valueOf(order.paidAt()));
        }
        out.put(
                "items",
                order.items().stream()
                        .map(i -> Map.of("description", i.description(), "unitPriceVnd", i.unitPriceVnd()))
                        .toList());
        return out;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không tuần tự hoá được kết quả tool", e);
        }
    }
}
