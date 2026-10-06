// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.domain.model.CustomerProfile;
import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.EventRef;
import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.CustomerProfilePort;
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
 *
 * <p>Tool điều khiển ({@link SupportAgentTools#CONTROL_TOOLS}) <b>không</b> đi qua đây — xem
 * {@code CustomerSupportAgentUseCase}.
 */
@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    /** Số đơn gần nhất đưa cho mô hình. Khách hỏi "đơn của tôi" thì gần như luôn là một trong số này. */
    private static final int RECENT_ORDERS = 5;

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
    private final CustomerProfilePort profiles;
    private final TicketBookingUseCase booking;
    private final ObjectMapper json;
    private final AgentMetrics metrics;

    public ToolDispatcher(
            OrderingClientPort ordering,
            CatalogClientPort catalog,
            VectorStorePort knowledge,
            CallerCredentialsPort credentials,
            CustomerProfilePort profiles,
            TicketBookingUseCase booking,
            ObjectMapper json,
            AgentMetrics metrics) {
        this.ordering = ordering;
        this.catalog = catalog;
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.profiles = profiles;
        this.booking = booking;
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
                case SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY -> getCustomerProfileAndHistory(call);
                case SupportAgentTools.INITIATE_TICKET_BOOKING -> initiateTicketBooking(call);
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
                            "Gọi getCustomerProfileAndHistory để lấy orderId của các đơn gần nhất."))));
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
     * Hồ sơ và đơn gần nhất của chính khách.
     *
     * <p>Hai lời gọi, hai service, và chúng hỏng độc lập: identity sập thì vẫn trả đơn kèm cờ nói
     * rõ là chưa lấy được tên, và ngược lại. Khách hỏi "đơn của tôi đâu" không cần biết tên mình để
     * nhận câu trả lời.
     *
     * <p><b>Email và số điện thoại được che bớt</b> trước khi vào prompt. Khách đang đọc dữ liệu
     * của chính mình, nhưng dữ liệu ấy đi qua nhà cung cấp mô hình và nằm lại trong hội thoại đã
     * lưu; "th***@gmail.com" đủ để khách nhận ra tài khoản mà không chép trọn một địa chỉ vào hai
     * nơi không cần nó.
     */
    private DispatchResult getCustomerProfileAndHistory(ToolInvocation call) {
        String token = credentials.currentAccessToken();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);

        try {
            CustomerProfile profile = profiles.currentProfile(token);
            Map<String, Object> customer = new LinkedHashMap<>();
            customer.put("fullName", profile.fullName());
            customer.put("emailMasked", maskEmail(profile.email()));
            customer.put("phoneMasked", maskPhone(profile.phone()));
            out.put("customer", customer);
        } catch (RemoteCallException e) {
            log.warn("Không tra được hồ sơ khách: {}", e.getMessage());
            out.put("customer", Map.of("error", "CHƯA LẤY ĐƯỢC HỒ SƠ — đừng gọi tên khách, cứ trả lời về đơn"));
        }

        try {
            List<OrderSummary> orders = ordering.listMyOrders(token, RECENT_ORDERS);
            out.put("orderCount", orders.size());
            if (orders.isEmpty()) {
                out.put("orders", List.of());
                out.put("hint", "Khách chưa có đơn nào. Nếu họ nói đã mua thì hỏi xem có dùng tài khoản khác không.");
            } else {
                out.put(
                        "orders",
                        orders.stream().map(ToolDispatcher::describeBrief).toList());
            }
        } catch (RemoteCallException e) {
            log.warn("Không tra được danh sách đơn: {}", e.getMessage());
            return DispatchResult.of(ToolOutcome.error(
                    call.callId(),
                    "Hệ thống đơn hàng tạm thời không phản hồi. ĐỪNG nói là khách không có đơn — "
                            + "hãy mời khách thử lại sau ít phút."));
        }
        return DispatchResult.of(ToolOutcome.ok(call.callId(), write(out)));
    }

    /**
     * Đặt vé hộ khách.
     *
     * <p>Mọi kết cục "không đặt được vì nghiệp vụ" (hết chỗ, vượt hạn mức, tham số sai) là kết quả
     * <i>thành công</i> với {@code booked: false} và một lý do — mô hình đọc rồi nói lại. Chỉ khi
     * một service không phản hồi mới là lỗi, và lúc đó câu đúng là "thử lại sau" chứ không phải
     * "hết vé".
     */
    private DispatchResult initiateTicketBooking(ToolInvocation call) {
        String slug = call.stringArg("slug");
        UUID sessionId = parseUuid(call.stringArg("sessionId"));
        String zoneCode = call.stringArg("zoneCode");
        Integer quantity = call.intArg("quantity");

        if (slug == null || slug.isBlank() || sessionId == null || zoneCode == null || zoneCode.isBlank()) {
            return DispatchResult.of(ToolOutcome.ok(
                    call.callId(),
                    write(Map.of(
                            "booked",
                            false,
                            "reason",
                            "THIẾU THAM SỐ",
                            "hint",
                            "Cần slug, sessionId (UUID) và zoneCode — lấy từ getEventDetails."))));
        }
        if (quantity == null) {
            return DispatchResult.of(ToolOutcome.ok(
                    call.callId(),
                    write(Map.of("booked", false, "reason", "THIẾU SỐ LƯỢNG", "hint", "Hỏi khách muốn mấy vé."))));
        }

        try {
            TicketBookingUseCase.BookingOutcome outcome =
                    booking.initiate(slug.trim(), sessionId, zoneCode.trim(), quantity, call.callId());
            return switch (outcome) {
                case TicketBookingUseCase.BookingOutcome.Rejected rejected -> DispatchResult.of(
                        ToolOutcome.ok(call.callId(), write(Map.of("booked", false, "reason", rejected.reason()))));
                case TicketBookingUseCase.BookingOutcome.Started started -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("booked", true);
                    out.put("eventTitle", started.event().title());
                    out.put("quantity", started.hold().quantity());
                    out.put("orderNumber", started.order().orderNumber());
                    out.put("orderId", started.order().orderId());
                    out.put("totalVnd", started.order().totalVnd());
                    out.put(
                            "holdExpiresVietnamTime",
                            VN_TIME.format(started.hold().expiresAt()));
                    if (started.order().paymentExpiresAt() != null) {
                        out.put(
                                "paymentExpiresVietnamTime",
                                VN_TIME.format(started.order().paymentExpiresAt()));
                    }
                    out.put("checkoutUrl", started.order().checkoutUrl());
                    out.put(
                            "nextStep",
                            "Báo khách số đơn, tổng tiền và hạn thanh toán, rồi đưa đúng checkoutUrl ở trên "
                                    + "để họ thanh toán. ĐỪNG tự viết đường dẫn khác.");
                    // Không kèm EventRef: khách vừa đặt xong, và "xem chỗ và mua vé" gắn vào lúc này
                    // là mời họ mua lần nữa. Đường đi tiếp duy nhất là checkoutUrl.
                    yield DispatchResult.of(ToolOutcome.ok(call.callId(), write(out)));
                }
            };
        } catch (RemoteCallException e) {
            log.warn("Không đặt được vé ({} / {}): {}", slug, zoneCode, e.getMessage());
            return DispatchResult.of(ToolOutcome.error(
                    call.callId(),
                    "Hệ thống đặt vé tạm thời không phản hồi. ĐỪNG nói là hết vé — hãy mời khách thử lại "
                            + "sau ít phút hoặc đặt trên trang sự kiện."));
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
        return DispatchResult.of(rules.map(r -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("found", true);
                    out.put("eventTitle", r.eventTitle());
                    out.put("rules", r.content());
                    // Chính sách hoàn vé nói bằng câu, không bằng cờ: mô hình đọc "không nhận hoàn
                    // vé" đúng hơn đọc `refundAllowed: false` rồi tự diễn giải.
                    out.put("refundPolicy", describeRefund(r));
                    return ToolOutcome.ok(call.callId(), write(out));
                })
                .orElseGet(() -> ToolOutcome.ok(
                        call.callId(),
                        write(Map.of("found", false, "reason", "CHƯA CÓ QUY ĐỊNH ĐƯỢC CÔNG BỐ CHO SỰ KIỆN NÀY")))));
    }

    private static String describeRefund(EventRules rules) {
        var policy = rules.refundPolicy();
        if (!policy.allowed()) {
            return "Sự kiện KHÔNG nhận hoàn vé.";
        }
        return policy.windowHours() == 0
                ? "Nhận yêu cầu hoàn vé; nhân viên xem xét từng trường hợp."
                : "Nhận yêu cầu hoàn vé trong " + policy.windowHours()
                        + " giờ kể từ lúc thanh toán; nhân viên xem xét từng trường hợp.";
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
     *
     * <p>Kèm {@code sessionId} và {@code zoneCode} vì đó là hai thứ {@code initiateTicketBooking}
     * cần — và là cách duy nhất để chúng vào prompt từ nguồn thật thay vì từ trí tưởng tượng.
     */
    private static Map<String, Object> describe(EventDetail event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("slug", event.slug());
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
                            s.put("sessionId", session.id());
                            if (session.startsAt() != null) {
                                s.put("startsVietnamTime", VN_TIME.format(session.startsAt()));
                            }
                            if (session.salesCloseAt() != null) {
                                s.put("ticketSalesCloseVietnamTime", VN_TIME.format(session.salesCloseAt()));
                            }
                            s.put(
                                    "tiers",
                                    session.tiers().stream()
                                            .map(tier -> {
                                                Map<String, Object> t = new LinkedHashMap<>();
                                                t.put("name", tier.name());
                                                t.put("priceVnd", tier.priceVnd());
                                                t.put("zoneCode", tier.zoneCode());
                                                return t;
                                            })
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
        out.put("orderId", order.orderId());
        out.put("orderNumber", order.orderNumber());
        out.put("status", order.status());
        out.put("totalVnd", order.totalVnd());
        if (order.paymentExpiresAt() != null) {
            out.put("paymentExpiresVietnamTime", VN_TIME.format(order.paymentExpiresAt()));
        }
        if (order.paidAt() != null) {
            out.put("paidVietnamTime", VN_TIME.format(order.paidAt()));
        }
        out.put(
                "items",
                order.items().stream()
                        .map(i -> Map.of("description", i.description(), "unitPriceVnd", i.unitPriceVnd()))
                        .toList());
        return out;
    }

    /** Một dòng trong lịch sử đơn — đủ để khách chọn, không đủ để chiếm cả prompt. */
    private static Map<String, Object> describeBrief(OrderSummary order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.orderId());
        out.put("orderNumber", order.orderNumber());
        out.put("status", order.status());
        out.put("totalVnd", order.totalVnd());
        out.put("ticketCount", order.items().size());
        if (order.paidAt() != null) {
            out.put("paidVietnamTime", VN_TIME.format(order.paidAt()));
        } else if (order.paymentExpiresAt() != null) {
            out.put("paymentExpiresVietnamTime", VN_TIME.format(order.paymentExpiresAt()));
        }
        return out;
    }

    /** "thien.lu@gmail.com" → "th***@gmail.com". Null hoặc không có @ thì trả về null. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return null;
        }
        String local = email.substring(0, at);
        String kept = local.substring(0, Math.min(2, local.length()));
        return kept + "***" + email.substring(at);
    }

    /** "0912345678" → "09*****678". Giữ đầu và đuôi vì đó là phần khách nhớ. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() < 6) {
            return "***";
        }
        return digits.substring(0, 2) + "*".repeat(digits.length() - 5) + digits.substring(digits.length() - 3);
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
