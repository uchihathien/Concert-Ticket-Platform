// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.SupportAgentPrompts;
import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.IncidentKind;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hai đường mở phiếu <b>có mục đích</b>: xin hoàn vé và báo sự cố.
 *
 * <p>Khác {@code escalateToHuman} — vốn là "trợ lý chịu" — hai đường này là trợ lý đã làm được
 * một phần việc: kiểm chính sách, gom đủ dữ liệu, xếp vào đúng khung. Người trực nhận một phiếu đã
 * có mã đơn, số tiền, kết quả xét chính sách, và loại sự cố — thay vì một câu "khách cần hỗ trợ".
 *
 * <h2>Chính sách hoàn vé là chốt TỪ CHỐI</h2>
 *
 * <p>{@link RefundPolicy} đủ điều kiện nghĩa là phiếu được mở cho người xem xét; không đủ thì trợ
 * lý nói thẳng với khách lý do và <b>không</b> mở phiếu. Hoàn tiền thật vẫn là việc của người và
 * sổ cái. Nhờ vậy những yêu cầu chắc chắn bị từ chối — sự kiện không nhận hoàn, quá hạn — không đi
 * vào hàng đợi chỉ để một người trực đọc rồi từ chối y hệt.
 *
 * <p>Sự kiện <b>chưa có</b> quy định thì mở phiếu, không từ chối: mặc định {@code RefundPolicy.NONE}
 * là "sai theo hướng an toàn" cho tool đọc quy định, nhưng ở đây từ chối một yêu cầu mà chưa ai
 * quyết chính sách là sai theo hướng mất khách. Để người quyết.
 */
@Service
public class SupportCaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(SupportCaseUseCase.class);

    /** Trạng thái duy nhất có thể hoàn — so chuỗi, không import enum của ordering (ranh giới ACL). */
    private static final String PAID = "PAID";

    /** Mốc mở phiếu theo giờ Việt Nam, cách khách đọc. */
    private static final DateTimeFormatter OPENED_AT =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final OrderingClientPort ordering;
    private final VectorStorePort knowledge;
    private final CallerCredentialsPort credentials;
    private final HandoffUseCase handoffs;
    private final Clock clock;
    private final ObjectMapper json;

    public SupportCaseUseCase(
            OrderingClientPort ordering,
            VectorStorePort knowledge,
            CallerCredentialsPort credentials,
            HandoffUseCase handoffs,
            Clock clock,
            ObjectMapper json) {
        this.ordering = ordering;
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.handoffs = handoffs;
        this.clock = clock;
        this.json = json;
    }

    /** Kết quả xin hoàn vé: hoặc từ chối ngay kèm lời giải thích, hoặc đã mở phiếu. */
    public sealed interface RefundOutcome {

        /**
         * @param explanation câu dành cho mô hình đọc rồi nói lại với khách — nêu đúng lý do, không
         *     hứa gì thêm
         */
        record Denied(String explanation) implements RefundOutcome {}

        record Opened(Handoff handoff, String orderNumber) implements RefundOutcome {}
    }

    /**
     * Khách xin hoàn vé một đơn.
     *
     * @param orderId mã đơn mô hình đọc từ câu khách — <b>chưa tin được</b>; ordering kiểm chủ sở
     *     hữu bằng token của khách, như mọi lần tra đơn
     * @param customerReason lý do khách nêu, mô hình chép lại
     * @param userQuery câu gốc của khách, để ghi vào hội thoại cùng với câu báo
     * @throws com.nexaticket.aichatbox.domain.port.RemoteCallException ordering không phản hồi —
     *     bên gọi biến nó thành kết quả tool lỗi, như với mọi tool khác
     */
    public RefundOutcome requestRefund(
            UUID sessionId, UUID userId, UUID orderId, String customerReason, String userQuery) {

        OrderSummary order;
        try {
            order = ordering.fetchOrder(orderId, credentials.currentAccessToken());
        } catch (OrderNotFoundException e) {
            return new RefundOutcome.Denied(
                    "Không có đơn nào mang mã này trong tài khoản của khách. Hãy hỏi lại mã đơn — nó nằm ở "
                            + "trang Đơn hàng của khách, dạng UUID.");
        }

        Optional<EventRules> rules = order.eventId() == null ? Optional.empty() : knowledge.findRules(order.eventId());
        RefundPolicy policy = rules.map(EventRules::refundPolicy).orElse(null);
        RefundPolicy.Eligibility eligibility =
                policy == null ? RefundPolicy.Eligibility.ELIGIBLE : policy.evaluate(order.paidAt(), clock.instant());

        // Đơn chưa trả tiền thì không có gì để hoàn, bất kể chính sách — kiểm trước cả khi không có
        // quy định, vì "huỷ đơn" là việc khách tự làm được trên trang đơn hàng.
        if (order.paidAt() == null) {
            return new RefundOutcome.Denied("Đơn " + order.orderNumber() + " chưa thanh toán nên không có gì để hoàn. "
                    + "Nếu khách không muốn mua nữa thì bấm Huỷ đơn ở trang Đơn hàng — chỗ sẽ được nhả ngay.");
        }
        // Đã trả tiền chưa đủ: đơn đã hoàn, đã huỷ, đang chờ đối soát… thì không còn gì để xin hoàn
        // nữa (R-13). Kiểm trước chính sách — chính sách chỉ có nghĩa với một đơn đang PAID.
        if (!PAID.equals(order.status())) {
            return new RefundOutcome.Denied(notPaidStatusExplanation(order));
        }
        switch (eligibility) {
            case NOT_ALLOWED -> {
                return new RefundOutcome.Denied("Sự kiện " + eventTitle(rules, order)
                        + " không nhận hoàn vé theo quy định của ban tổ chức. Nói rõ với khách rằng đây là "
                        + "chính sách của sự kiện, không phải của NexaTicket, và không hứa ngoại lệ.");
            }
            case WINDOW_PASSED -> {
                return new RefundOutcome.Denied("Sự kiện " + eventTitle(rules, order) + " chỉ nhận hoàn vé trong "
                        + policy.windowHours() + " giờ kể từ lúc thanh toán, và đơn " + order.orderNumber()
                        + " đã quá hạn đó. Nói rõ mốc với khách, không hứa ngoại lệ.");
            }
            case NOT_PAID, ELIGIBLE -> {
                // NOT_PAID đã được chặn ở trên; ELIGIBLE đi tiếp.
            }
        }

        // Đã có một yêu cầu đang chờ xem xét cho đúng đơn này (có thể ở phiên khác) thì không mở
        // phiếu thứ hai — hai phiếu là hai lần hoàn tiền tiềm năng. Trả Denied chứ không trả phiếu
        // cũ: `Opened` khiến agent kết thúc lượt như thể lượt đã được ghi vào hội thoại này.
        Optional<Handoff> pending = handoffs.findOpenRefund(userId, order.orderId());
        if (pending.isPresent()) {
            return new RefundOutcome.Denied("Đơn " + order.orderNumber() + " đã có một yêu cầu hoàn vé đang được "
                    + "xem xét, mở lúc " + OPENED_AT.format(pending.get().requestedAt()) + ". Báo khách rằng bộ "
                    + "phận hỗ trợ đang xử lý yêu cầu đó và dặn khách không cần gửi yêu cầu mới.");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "REFUND");
        details.put("orderId", order.orderId());
        details.put("orderNumber", order.orderNumber());
        details.put("totalVnd", order.totalVnd());
        details.put("paidAt", String.valueOf(order.paidAt()));
        details.put("eventId", order.eventId());
        details.put("policy", policy == null ? "UNKNOWN" : (policy.allowed() ? "ALLOWED" : "NOT_ALLOWED"));
        details.put("policyWindowHours", policy == null ? null : policy.windowHours());
        details.put("customerReason", blankToNull(customerReason));

        String reason = "[Hoàn vé] đơn " + order.orderNumber() + " · " + formatVnd(order.totalVnd())
                + (policy == null ? " · sự kiện chưa khai chính sách" : "")
                + (blankToNull(customerReason) == null ? "" : ": " + customerReason.strip());
        String reply = SupportAgentPrompts.refundRequestOpenedMessage(order.orderNumber());

        Handoff opened = handoffs.escalateWithTurn(
                sessionId,
                userId,
                HandoffTrigger.CUSTOMER_REQUEST,
                SupportIntent.REFUND,
                reason,
                write(details),
                userQuery,
                reply);
        log.info("Mở phiếu hoàn vé cho đơn {} (phiên {})", order.orderNumber(), sessionId);
        return new RefundOutcome.Opened(opened, order.orderNumber());
    }

    /**
     * Khách báo một sự cố.
     *
     * <p>Mã đơn, nếu có, được tra lại bằng token của khách để lấy số đơn đọc được và để chắc rằng nó
     * là của họ. Một sự cố thật không bị chặn vì lời gọi phụ này — phiếu luôn được mở — nhưng khoá
     * {@code orderId} (khoá người trực tin) CHỈ mang mã đã xác minh (R-12):
     *
     * <ul>
     *   <li>đơn của khách → {@code orderId}, {@code orderVerified: true};
     *   <li>không có trong tài khoản khách → bỏ hẳn mã, {@code orderLookup: NOT_IN_ACCOUNT} — mã đơn
     *       của người khác kèm checklist "phát lại vé" là đúng cảnh cần tránh;
     *   <li>ordering không phản hồi → mã thô ở {@code claimedOrderId}, {@code orderLookup:
     *       UNAVAILABLE}: sự cố thanh toán đúng lúc ordering hỏng là lúc người trực cần mã nhất.
     * </ul>
     *
     * @param orderId có thể {@code null} với loại sự cố không cần đơn
     */
    public Handoff reportIncident(
            UUID sessionId, UUID userId, IncidentKind kind, String description, UUID orderId, String userQuery) {

        String orderNumber = null;
        String orderRef = null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name());
        details.put("kindLabel", kind.label());
        if (orderId != null) {
            try {
                orderNumber = ordering.fetchOrder(orderId, credentials.currentAccessToken())
                        .orderNumber();
                orderRef = orderNumber;
                details.put("orderId", orderId);
                details.put("orderVerified", true);
            } catch (OrderNotFoundException e) {
                log.warn("Đơn {} không thuộc tài khoản khách khi mở phiếu sự cố — bỏ mã khỏi phiếu", orderId);
                details.put("orderVerified", false);
                details.put("orderLookup", "NOT_IN_ACCOUNT");
            } catch (RemoteCallException e) {
                log.warn("Không tra được đơn {} khi mở phiếu sự cố: {}", orderId, e.getMessage());
                orderRef = "chưa xác minh " + shortId(orderId);
                details.put("claimedOrderId", orderId);
                details.put("orderVerified", false);
                details.put("orderLookup", "UNAVAILABLE");
            }
        }
        details.put("orderNumber", orderNumber);
        details.put("description", blankToNull(description));

        Handoff opened = handoffs.escalateWithTurn(
                sessionId,
                userId,
                HandoffTrigger.CUSTOMER_REQUEST,
                SupportIntent.INCIDENT,
                IncidentTemplates.composeReason(kind, orderRef, description),
                write(details),
                userQuery,
                SupportAgentPrompts.incidentOpenedMessage(kind, orderNumber));
        log.info("Mở phiếu sự cố {} (phiên {}, đơn {})", kind, sessionId, orderNumber);
        return opened;
    }

    /** Mỗi trạng thái không-PAID một lời giải thích riêng, cùng giọng với các nhánh Denied khác. */
    private static String notPaidStatusExplanation(OrderSummary order) {
        String n = order.orderNumber();
        return switch (order.status()) {
            case "REFUNDED" -> "Đơn " + n + " đã được hoàn tiền rồi, không còn gì để hoàn thêm. Nếu khách chưa "
                    + "thấy tiền về thì đó là thời gian xử lý của ngân hàng — không mở yêu cầu mới.";
            case "CANCELLED" -> "Đơn " + n + " đã bị huỷ nên không có vé nào để hoàn. Nếu khách đã bị trừ tiền "
                    + "cho đơn này thì đề nghị báo sự cố thanh toán thay vì xin hoàn vé.";
            case "EXPIRED" -> "Đơn " + n + " đã hết hạn thanh toán và không thành vé, nên không có gì để hoàn. "
                    + "Nếu khách đã bị trừ tiền thì đề nghị báo sự cố thanh toán.";
            case "MANUAL_REVIEW" -> "Đơn " + n + " đang được bộ phận đối soát kiểm tra thanh toán. Chưa xin "
                    + "hoàn được cho tới khi đối soát xong — báo khách chờ kết quả, không mở yêu cầu mới.";
            default -> "Đơn " + n + " đang ở trạng thái " + order.status() + ", không phải đơn đã thanh toán "
                    + "xong, nên chưa xin hoàn được. Đề nghị khách liên hệ hỗ trợ nếu cần.";
        };
    }

    private static String eventTitle(Optional<EventRules> rules, OrderSummary order) {
        return rules.map(EventRules::eventTitle).orElse("của đơn " + order.orderNumber());
    }

    /** "1.800.000đ" — cách khách đọc số tiền, không phải "1800000". */
    private static String formatVnd(long amount) {
        return String.format(java.util.Locale.forLanguageTag("vi-VN"), "%,dđ", amount)
                .replace(',', '.');
    }

    /** Tám ký tự đầu của UUID — đủ để người trực nhận ra, không chiếm cả dòng. */
    private static String shortId(UUID id) {
        return id == null ? null : id.toString().substring(0, 8) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không tuần tự hoá được chi tiết phiếu", e);
        }
    }
}
