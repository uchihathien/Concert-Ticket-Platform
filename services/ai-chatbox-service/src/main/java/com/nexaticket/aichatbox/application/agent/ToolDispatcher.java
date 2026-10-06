// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.EventRef;
import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Giờ trả cho mô hình là giờ Việt Nam, đã định dạng sẵn.
     *
     * <p>Catalog lưu và trả {@link java.time.Instant} theo UTC. Đưa nguyên chuỗi UTC cho mô hình là
     * giao cho nó một phép đổi múi giờ — và một mô hình 7B làm sai phép đó thì khách nhận giờ diễn
     * lệch 7 tiếng, nghe vẫn rất tự tin. Đổi ở đây là đổi một lần, đúng mọi lần.
     */
    private static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' d/M/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final OrderingClientPort ordering;
    private final CatalogClientPort catalog;
    private final VectorStorePort knowledge;
    private final CallerCredentialsPort credentials;
    private final ObjectMapper json;
    private final AgentMetrics metrics;

    public ToolDispatcher(
            OrderingClientPort ordering,
            CatalogClientPort catalog,
            VectorStorePort knowledge,
            CallerCredentialsPort credentials,
            ObjectMapper json,
            AgentMetrics metrics) {
        this.ordering = ordering;
        this.catalog = catalog;
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * Kết quả một lời gọi tool: thứ mô hình đọc, kèm thứ GIAO DIỆN cần.
     *
     * <p>Hai thứ khác nhau và không thay thế nhau được. {@code outcome.payload()} là JSON cho mô
     * hình. {@code events} là những sự kiện catalog vừa trả về — giao diện dùng chúng để dựng đường
     * dẫn mua vé, và chúng phải đi ra khỏi đây dưới dạng dữ liệu có kiểu chứ không phải bằng cách
     * đọc lại chuỗi JSON ở tầng trên.
     */
    public record DispatchResult(ToolOutcome outcome, List<EventRef> events) {

        static DispatchResult of(ToolOutcome outcome) {
            return new DispatchResult(outcome, List.of());
        }
    }

    public DispatchResult dispatch(ToolInvocation call) {
        DispatchResult result = run(call);
        metrics.recordTool(call.toolName(), result.outcome().failed() ? "failed" : "ok");
        return result;
    }

    private DispatchResult run(ToolInvocation call) {
        try {
            return switch (call.toolName()) {
                case SupportAgentTools.GET_ORDER_STATUS -> getOrderStatus(call);
                case SupportAgentTools.GET_EVENT_RULES -> getEventRules(call);
                case SupportAgentTools.FIND_EVENTS -> findEvents(call);
                case SupportAgentTools.GET_EVENT_DETAILS -> getEventDetails(call);
                    // Mô hình gọi một tool không tồn tại. Hiếm, nhưng xảy ra — và im lặng ở đây
                    // biến nó thành một request sai định dạng ở vòng sau.
                default -> DispatchResult.of(
                        ToolOutcome.error(call.callId(), "Tool không tồn tại: " + call.toolName()));
            };
        } catch (RuntimeException e) {
            // Lưới cuối. Bất cứ thứ gì lọt tới đây là lỗi lập trình, nhưng nó vẫn không được phép
            // làm hỏng lượt chat của khách.
            log.error("Tool {} ném ngoại lệ không lường trước", call.toolName(), e);
            return DispatchResult.of(
                    ToolOutcome.error(call.callId(), "Hệ thống tra cứu gặp sự cố. Hãy nói với khách là thử lại sau."));
        }
    }

    private DispatchResult getOrderStatus(ToolInvocation call) {
        UUID orderId = parseUuid(call.stringArg("orderId"));
        if (orderId == null) {
            // Không phải lỗi hệ thống: mô hình đưa sai tham số, và nó tự sửa được nếu ta nói rõ.
            return DispatchResult.of(ToolOutcome.ok(
                    call.callId(),
                    write(Map.of(
                            "found",
                            false,
                            "reason",
                            "MÃ ĐƠN KHÔNG HỢP LỆ",
                            "hint",
                            "Hãy hỏi khách mã đơn hàng dạng UUID."))));
        }
        try {
            OrderSummary order = ordering.fetchOrder(orderId, credentials.currentAccessToken());
            return DispatchResult.of(ToolOutcome.ok(call.callId(), write(describe(order))));
        } catch (OrderNotFoundException e) {
            return DispatchResult.of(ToolOutcome.ok(
                    call.callId(),
                    write(Map.of("found", false, "reason", "KHÔNG CÓ ĐƠN NÀY TRONG TÀI KHOẢN CỦA KHÁCH"))));
        } catch (RemoteCallException e) {
            log.warn("Không tra được đơn {}: {}", orderId, e.getMessage());
            return DispatchResult.of(ToolOutcome.error(
                    call.callId(),
                    "Hệ thống đơn hàng tạm thời không phản hồi. ĐỪNG nói là không tìm thấy đơn — "
                            + "hãy mời khách thử lại sau ít phút."));
        }
    }

    /**
     * Tìm sự kiện theo tên.
     *
     * <p>Không có kết quả là kết quả <b>thành công</b> với {@code found: false} — y như đơn hàng
     * không tồn tại. Đánh dấu lỗi ở đây thì mô hình nói "hệ thống đang bận" trong khi sự thật là
     * NexaTicket không bán vé sự kiện đó, và khách sẽ chờ rồi hỏi lại.
     *
     * <p>Kèm {@code hint} khi rỗng: một mô hình nhỏ rất dễ gọi lại đúng tham số vừa trượt. Nói
     * trước cho nó biết nên thử cách khác sẽ cắt được một vòng lặp có tính tiền.
     */
    private DispatchResult findEvents(ToolInvocation call) {
        String query = call.stringArg("query");
        String city = call.stringArg("city");
        String category = call.stringArg("category");

        try {
            List<EventBrief> found = catalog.searchEvents(query, city, category, 5);
            if (found.isEmpty()) {
                return DispatchResult.of(ToolOutcome.ok(
                        call.callId(),
                        write(Map.of(
                                "found",
                                false,
                                "reason",
                                "KHÔNG CÓ SỰ KIỆN NÀO KHỚP",
                                "hint",
                                "Thử lại với ít chữ hơn trong query, hoặc bỏ trống city. "
                                        + "Nếu vẫn không có thì nói thẳng với khách là NexaTicket "
                                        + "chưa bán vé sự kiện này."))));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("count", found.size());
            out.put("events", found.stream().map(ToolDispatcher::describe).toList());
            // Mọi sự kiện tìm được đều đi kèm ra ngoài: giao diện dựng một đường dẫn mua vé cho từng
            // cái, và khách chọn bằng cách bấm thay vì gõ lại tên.
            return new DispatchResult(
                    ToolOutcome.ok(call.callId(), write(out)),
                    found.stream()
                            .map(event -> new EventRef(event.slug(), event.title()))
                            .toList());
        } catch (RemoteCallException e) {
            log.warn("Không tra được danh mục sự kiện: {}", e.getMessage());
            return DispatchResult.of(ToolOutcome.error(
                    call.callId(),
                    "Hệ thống danh mục tạm thời không phản hồi. ĐỪNG nói là không có sự kiện nào — "
                            + "hãy mời khách thử lại sau ít phút."));
        }
    }

    private DispatchResult getEventDetails(ToolInvocation call) {
        String slug = call.stringArg("slug");
        if (slug == null || slug.isBlank()) {
            return DispatchResult.of(ToolOutcome.ok(
                    call.callId(),
                    write(Map.of(
                            "found", false, "reason", "THIẾU SLUG", "hint", "Gọi findEvents trước để lấy slug."))));
        }
        try {
            return catalog.findEventBySlug(slug.trim())
                    .map(detail -> new DispatchResult(
                            ToolOutcome.ok(call.callId(), write(describe(detail))),
                            List.of(new EventRef(detail.slug(), detail.title()))))
                    .orElseGet(() -> DispatchResult.of(ToolOutcome.ok(
                            call.callId(),
                            write(Map.of(
                                    "found",
                                    false,
                                    "reason",
                                    "KHÔNG CÓ SỰ KIỆN NÀO MANG SLUG NÀY",
                                    "hint",
                                    "Đừng tự dựng slug từ tên. Gọi findEvents để lấy slug đúng.")))));
        } catch (RemoteCallException e) {
            log.warn("Không tra được chi tiết sự kiện {}: {}", slug, e.getMessage());
            return DispatchResult.of(ToolOutcome.error(
                    call.callId(), "Hệ thống danh mục tạm thời không phản hồi. Hãy mời khách thử lại sau ít phút."));
        }
    }

    private DispatchResult getEventRules(ToolInvocation call) {
        UUID eventId = parseUuid(call.stringArg("eventId"));
        if (eventId == null) {
            return DispatchResult.of(
                    ToolOutcome.ok(call.callId(), write(Map.of("found", false, "reason", "MÃ SỰ KIỆN KHÔNG HỢP LỆ"))));
        }
        Optional<EventRules> rules = knowledge.findRules(eventId);
        return DispatchResult.of(rules.map(r -> ToolOutcome.ok(
                        call.callId(),
                        write(Map.of("found", true, "eventTitle", r.eventTitle(), "rules", r.content()))))
                .orElseGet(() -> ToolOutcome.ok(
                        call.callId(),
                        write(Map.of("found", false, "reason", "CHƯA CÓ QUY ĐỊNH ĐƯỢC CÔNG BỐ CHO SỰ KIỆN NÀY")))));
    }

    /** Một dòng trong kết quả tìm kiếm. Giá kèm đơn vị trong TÊN khoá, để mô hình không tự đoán. */
    private static Map<String, Object> describe(EventBrief event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", event.slug());
        out.put("title", event.title());
        out.put("city", event.city());
        out.put("venueName", event.venueName());
        if (event.nextSessionAt() != null) {
            out.put("nextSessionVietnamTime", VN_TIME.format(event.nextSessionAt()));
        }
        if (event.fromPriceVnd() != null) {
            out.put("fromPriceVnd", event.fromPriceVnd());
        }
        out.put("sessionCount", event.sessionCount());
        // Nhiều suất thì nói THẲNG trong dữ liệu rằng ngày của các suất còn lại không có ở đây.
        //
        // Đo được lỗi thật: sự kiện hai suất (2/10 và 9/10) chỉ trả về ngày suất gần nhất kèm
        // sessionCount=2, và mô hình lấp chỗ trống bằng một ngày nó tự nghĩ ra — "2/10 và 7/10",
        // nghe rất tự tin. Một khoảng trống trong dữ liệu là lời mời bịa; bịt nó bằng câu chữ
        // trong chính kết quả tool đáng tin hơn hẳn một dòng cấm trong prompt hệ thống.
        if (event.sessionCount() > 1) {
            out.put(
                    "otherSessionDates",
                    "KHÔNG CÓ Ở ĐÂY — còn " + (event.sessionCount() - 1)
                            + " suất khác. Gọi getEventDetails để biết ngày, ĐỪNG tự nêu ngày nào.");
        }
        return out;
    }

    /**
     * Chi tiết một sự kiện.
     *
     * <p>Kèm {@code customerUrl} vì đó là thứ hữu ích nhất mà mô hình có thể đưa cho khách: một
     * đường dẫn bấm được để tự chọn chỗ. Không kèm thì mô hình tự dựng đường dẫn, và nó dựng sai.
     */
    private static Map<String, Object> describe(EventDetail event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("title", event.title());
        if (event.summary() != null) {
            out.put("summary", event.summary());
        }
        if (event.description() != null) {
            out.put("description", event.description());
        }
        out.put("city", event.city());
        out.put("venueName", event.venueName());
        if (event.venueAddress() != null) {
            out.put("venueAddress", event.venueAddress());
        }
        out.put(
                "sessions",
                event.sessions().stream()
                        .map(session -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            if (session.startsAt() != null) {
                                s.put("startsVietnamTime", VN_TIME.format(session.startsAt()));
                            }
                            if (session.salesCloseAt() != null) {
                                s.put("ticketSalesCloseVietnamTime", VN_TIME.format(session.salesCloseAt()));
                            }
                            s.put(
                                    "tiers",
                                    session.tiers().stream()
                                            .map(tier -> Map.of("name", tier.name(), "priceVnd", tier.priceVnd()))
                                            .toList());
                            return s;
                        })
                        .toList());
        out.put("customerUrl", "/events/" + event.slug());
        return out;
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
