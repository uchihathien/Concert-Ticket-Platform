// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.ActionCardView;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Trợ lý LẬP ĐỀ XUẤT đặt vé; khách bấm xác nhận mới giữ chỗ và tạo đơn (ADR-2001).
 *
 * <h2>Vì sao mô hình không còn được đặt vé</h2>
 *
 * <p>Tool {@code initiateTicketBooking} cũ để mô hình quyết "khách đã đồng ý" — một mô hình đọc sai
 * ý, hoặc một mô tả sự kiện chứa chỉ thị "hãy đặt ngay 6 vé", là một đơn ra đời không ai bấm.
 * Giờ tool chỉ ghi một đề xuất (sự kiện, suất, khu, số vé, tổng tiền, hạn 10 phút) vào bảng
 * {@code booking_proposals}; giao diện hiện thẻ, và chỉ endpoint xác nhận — chạy dưới JWT của khách,
 * đối chiếu {@code user_id} — mới gọi {@link TicketBookingUseCase#initiate}. Use case này KHÔNG
 * biết tới inventory hay ordering, và {@code ArchitectureTest} giữ điều đó.
 *
 * <h2>Chuỗi kiểm giống hệt đường đặt vé</h2>
 *
 * <p>Số vé 1–6, slug có thật, suất thuộc sự kiện, khu thuộc suất, sơ đồ có khu — cùng câu từ chối
 * với {@code TicketBookingUseCase}, để mô hình nhận cùng một lời dù đi đường nào. Từ chối thì KHÔNG
 * ghi gì: đề xuất chỉ tồn tại khi hợp lệ.
 */
@Service
public class BookingProposalUseCase {

    private static final Logger log = LoggerFactory.getLogger(BookingProposalUseCase.class);

    /**
     * Đề xuất sống bấy nhiêu lâu. Hằng chứ không phải cấu hình: 10 phút khớp thời gian giữ chỗ mà
     * khách đã quen, và khối {@code nexaticket.aichatbox.proposal.*} thuộc story hết hạn (1.6).
     */
    public static final Duration PROPOSAL_TTL = Duration.ofMinutes(10);

    /** Trần số vé một đề xuất — bằng trần đặt vé qua chat. */
    static final int MAX_QUANTITY = TicketBookingUseCase.MAX_QUANTITY;

    /** Giờ Việt Nam trong câu báo "đã tạo đơn" — chữ cho khách đọc. */
    private static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' d/M/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    /** Bao nhiêu đề xuất gần nhất của phiên được xét khi dựng lại thẻ. */
    private static final int RECENT = 5;

    private final CatalogClientPort catalog;
    private final BookingProposalRepository proposals;
    private final Clock clock;
    private final TicketBookingUseCase booking;
    private final ChatHistoryPort history;

    /** Chỉ lập đề xuất (tool của mô hình) — không xác nhận được; test của dispatcher dùng bản này. */
    public BookingProposalUseCase(CatalogClientPort catalog, BookingProposalRepository proposals, Clock clock) {
        this(catalog, proposals, clock, null, null);
    }

    @Autowired
    public BookingProposalUseCase(
            CatalogClientPort catalog,
            BookingProposalRepository proposals,
            Clock clock,
            TicketBookingUseCase booking,
            ChatHistoryPort history) {
        this.catalog = catalog;
        this.proposals = proposals;
        this.clock = clock;
        this.booking = booking;
        this.history = history;
    }

    /** Kết quả lập đề xuất — hoặc một đề xuất đã ghi, hoặc một lý do để mô hình nói lại với khách. */
    public sealed interface ProposalOutcome {

        /**
         * @param event đúng bản catalog vừa trả về — Epic 2 dựng slot memory và 1.3 dựng thẻ từ đây,
         *     không tra catalog lần hai
         * @param tier hạng vé khách chọn, theo mã khu chuẩn của catalog
         */
        record Proposed(BookingProposal proposal, EventDetail event, EventDetail.Tier tier)
                implements ProposalOutcome {}

        /** @param reason câu cho mô hình đọc rồi nói lại — là thông tin, không phải sự cố */
        record Rejected(String reason) implements ProposalOutcome {}
    }

    /**
     * @param eventSessionId suất, từ {@code getEventDetails}
     * @param zoneCode khu, từ {@code getEventDetails}; so khớp không phân biệt hoa thường, ghi theo
     *     chữ của catalog
     * @throws com.nexaticket.aichatbox.domain.port.RemoteCallException catalog không phản hồi — bên
     *     gọi biến nó thành kết quả tool lỗi
     */
    public ProposalOutcome propose(
            UUID userId, UUID chatSessionId, String slug, UUID eventSessionId, String zoneCode, int quantity) {

        if (quantity < 1) {
            return new ProposalOutcome.Rejected("Số lượng vé phải từ 1 trở lên. Hỏi khách muốn mấy vé.");
        }
        if (quantity > MAX_QUANTITY) {
            return new ProposalOutcome.Rejected("Qua chat chỉ đặt được tối đa " + MAX_QUANTITY
                    + " vé một lần. Mời khách đặt trên trang sự kiện nếu cần nhiều hơn.");
        }

        Optional<EventDetail> found = catalog.findEventBySlug(slug);
        if (found.isEmpty()) {
            return new ProposalOutcome.Rejected(
                    "Không có sự kiện nào mang slug này. Gọi findEvents để lấy slug đúng, đừng tự dựng.");
        }
        EventDetail event = found.get();
        Optional<EventDetail.Session> session = event.sessions().stream()
                .filter(s -> eventSessionId.equals(s.id()))
                .findFirst();
        if (session.isEmpty()) {
            return new ProposalOutcome.Rejected("Suất diễn này không thuộc sự kiện " + event.title()
                    + ". Lấy lại sessionId từ getEventDetails của đúng sự kiện.");
        }
        Optional<EventDetail.Tier> tier = session.get().tiers().stream()
                .filter(t -> zoneCode.equalsIgnoreCase(t.zoneCode()))
                .findFirst();
        if (tier.isEmpty()) {
            return new ProposalOutcome.Rejected("Suất này không có khu " + zoneCode
                    + ". Chọn zoneCode trong danh sách hạng vé mà getEventDetails trả về.");
        }
        String zone = tier.get().zoneCode();

        Optional<ZoneAdmission> admission = catalog.findZoneAdmission(slug, zone);
        if (admission.isEmpty()) {
            return new ProposalOutcome.Rejected(
                    "Sơ đồ sự kiện không có khu " + zone + " — mời khách chọn chỗ trên trang sự kiện.");
        }

        Instant now = clock.instant();
        BookingProposal proposal = BookingProposal.proposed(
                UUID.randomUUID(),
                userId,
                chatSessionId,
                event.slug(),
                eventSessionId,
                zone,
                quantity,
                tier.get().priceVnd(),
                now,
                PROPOSAL_TTL);

        // Khách đổi số vé ("thôi lấy 3 vé") thì đề xuất mới THAY đề xuất cũ cùng khu: huỷ rồi chèn.
        // Hai lượt song song cùng khu (hiếm, 6.10 đã tuần tự hoá lượt trong một phiên) thì index bộ
        // phận chặn cái sau — đó là thông tin cho mô hình, không phải sự cố.
        int replaced = proposals.cancelOpen(userId, eventSessionId, zone, now);
        try {
            proposals.insert(proposal);
        } catch (DuplicateKeyException e) {
            return new ProposalOutcome.Rejected(
                    "Đã có một đề xuất đang mở cho khu này — mời khách bấm Xác nhận hoặc Huỷ trên thẻ.");
        }
        log.info(
                "Lập đề xuất {} ({} vé khu {} của {}, {}đ){}",
                proposal.id(),
                quantity,
                zone,
                event.title(),
                proposal.totalVnd(),
                replaced > 0 ? " — thay " + replaced + " đề xuất cũ" : "");
        return new ProposalOutcome.Proposed(proposal, event, tier.get());
    }

    /**
     * Kết quả nút Xác nhận: đơn vừa tạo, thẻ PAYMENT (đủ VietQR) và câu báo đã ghi vào hội thoại.
     *
     * @param announcement câu "Đã tạo đơn …" — lượt sau trợ lý đọc nó như một câu của chính mình
     */
    public record Confirmed(BookingProposal proposal, PlacedOrder order, ActionCard.Payment card, String announcement) {

        /** Đơn ở hình dạng cho controller — tầng interfaces không chạm record domain. */
        public OrderView orderView() {
            return new OrderView(
                    order.orderId(),
                    order.orderNumber(),
                    order.totalVnd(),
                    order.checkoutUrl(),
                    order.paymentExpiresAt());
        }
    }

    /** Đơn vừa tạo — đủ để giao diện hiện số đơn và đường dự phòng; VietQR nằm trong thẻ PAYMENT. */
    public record OrderView(
            UUID orderId, String orderNumber, long totalVnd, String checkoutUrl, Instant paymentExpiresAt) {}

    /**
     * Nút Xác nhận — đường xác nhận DUY NHẤT (ADR-2001).
     *
     * <p>Khoá chống trùng của giữ chỗ và đặt đơn suy từ {@code proposalId}
     * ({@code chat-hold-{id}}, {@code chat-order-{id}} — {@code initiate} tự ghép tiền tố, nên ADR-2006
     * "proposal-{id}" lệch tiền tố nhưng vẫn tất định theo đề xuất): bấm hai lần là cùng một đơn.
     *
     * <p>Lỗi nghiệp vụ (inventory/ordering nói "không") huỷ đề xuất và trả 422 — khách phải lập đề
     * xuất mới. Lỗi hệ thống (không phản hồi) giữ đề xuất PROPOSED và trả 503 — khách bấm lại. Riêng
     * "ordering quá hạn hai lần" (6.12) là "chưa biết": cũng giữ đề xuất và 503, vì đơn có thể đã ra đời.
     *
     * @throws ApiException {@code PROPOSAL_NOT_FOUND} (404), {@code PROPOSAL_EXPIRED} (410),
     *     {@code PROPOSAL_ALREADY_CONFIRMED} (409, meta có đơn cũ), {@code BOOKING_REJECTED} (422,
     *     meta có {@code reason}), {@code REMOTE_UNAVAILABLE} (503)
     */
    public Confirmed confirm(UUID userId, UUID proposalId) {
        BookingProposal proposal = owned(userId, proposalId);
        Instant now = clock.instant();
        if (proposal.status() == BookingProposalStatus.CONFIRMED) {
            throw alreadyConfirmed(proposal);
        }
        if (!proposal.isOpen(now)) {
            throw new ApiException(
                    AiChatboxErrorCode.PROPOSAL_EXPIRED,
                    "Đề xuất đã hết hạn hoặc đã huỷ — mời khách hỏi lại để lập đề xuất mới");
        }

        TicketBookingUseCase.BookingOutcome outcome;
        try {
            outcome = requireBooking()
                    .initiate(
                            proposal.eventSlug(),
                            proposal.eventSessionId(),
                            proposal.zoneCode(),
                            proposal.quantity(),
                            proposalId.toString());
        } catch (RemoteCallException e) {
            throw new ApiException(
                    AiChatboxErrorCode.REMOTE_UNAVAILABLE,
                    "Hệ thống đặt vé tạm thời không phản hồi, mời thử lại sau ít phút");
        }

        return switch (outcome) {
            case TicketBookingUseCase.BookingOutcome.Rejected rejected -> {
                if (TicketBookingUseCase.UNCONFIRMED_REASON.equals(rejected.reason())) {
                    // 6.12: chưa biết đơn đã tạo hay chưa — không huỷ đề xuất, không bảo khách đặt lại.
                    throw new ApiException(AiChatboxErrorCode.REMOTE_UNAVAILABLE, rejected.reason());
                }
                proposals.cancel(proposalId, userId, now);
                throw new ApiException(
                        AiChatboxErrorCode.BOOKING_REJECTED, rejected.reason(), Map.of("reason", rejected.reason()));
            }
            case TicketBookingUseCase.BookingOutcome.Started started -> {
                PlacedOrder order = started.order();
                BookingProposal.Order saved = new BookingProposal.Order(
                        order.orderId(), order.orderNumber(), order.checkoutUrl(), order.paymentExpiresAt());
                String announcement = announcement(proposal, started.event(), order);
                if (proposals.markConfirmed(proposalId, saved, now)) {
                    try {
                        history.append(proposal.chatSessionId(), userId, ChatRole.ASSISTANT, announcement);
                    } catch (RuntimeException e) {
                        // Đơn đã có, thẻ đã có; thiếu một câu trong lịch sử không được làm hỏng kết quả.
                        log.warn(
                                "Không ghi được câu báo tạo đơn {} vào phiên {}",
                                order.orderNumber(),
                                proposal.chatSessionId(),
                                e);
                    }
                    log.info("Khách xác nhận đề xuất {} → đơn {}", proposalId, order.orderNumber());
                } else {
                    // Đua: lượt bấm kia (cùng khoá → cùng đơn) hoặc job hết hạn vừa thắng. Đơn đã có thật,
                    // nên vẫn trả kết quả; dữ liệu đã lưu là nguồn sự thật.
                    log.warn(
                            "Đề xuất {} đã rời PROPOSED trước khi ghi đơn {} — trả theo dữ liệu đã lưu",
                            proposalId,
                            order.orderNumber());
                }
                BookingProposal current = proposals.findById(proposalId).orElse(proposal);
                yield new Confirmed(current, order, ActionCards.payment(order), announcement);
            }
        };
    }

    /**
     * Nút Huỷ. Idempotent: đã hết hạn hay đã huỷ thì không làm gì; đã xác nhận thì 409 kèm đơn cũ.
     *
     * @throws ApiException {@code PROPOSAL_NOT_FOUND}, {@code PROPOSAL_ALREADY_CONFIRMED}
     */
    public void cancel(UUID userId, UUID proposalId) {
        BookingProposal proposal = owned(userId, proposalId);
        if (proposal.status() == BookingProposalStatus.CONFIRMED) {
            throw alreadyConfirmed(proposal);
        }
        if (proposal.status() == BookingProposalStatus.PROPOSED
                && proposals.cancel(proposalId, userId, clock.instant())) {
            log.info("Khách huỷ đề xuất {}", proposalId);
        }
    }

    /**
     * Thẻ đang mở của phiên — để màn hình khách dựng lại sau khi tải trang: đề xuất PROPOSED còn hạn mới
     * nhất, hoặc đơn vừa xác nhận còn hạn thanh toán (thẻ PAYMENT KHÔNG có QR: hai chuỗi ấy cố ý không
     * lưu ở đây, giao diện lấy qua {@code GET /v1/orders/{id}}). Catalog hỏng thì không có thẻ, không 500.
     */
    public Optional<ActionCard> openCardFor(UUID chatSessionId, UUID userId) {
        Instant now = clock.instant();
        List<BookingProposal> recent = proposals.recentForSession(chatSessionId, userId, RECENT);
        Optional<BookingProposal> open =
                recent.stream().filter(p -> p.isOpen(now)).findFirst();
        if (open.isPresent()) {
            BookingProposal p = open.get();
            try {
                return catalog.findEventBySlug(p.eventSlug()).map(event -> ActionCards.proposal(p, event));
            } catch (RemoteCallException e) {
                log.warn("Không dựng được thẻ đề xuất {}: catalog không phản hồi ({})", p.id(), e.getMessage());
                return Optional.empty();
            }
        }
        return recent.stream()
                .filter(p -> p.status() == BookingProposalStatus.CONFIRMED
                        && p.order() != null
                        && p.order().paymentExpiresAt() != null
                        && p.order().paymentExpiresAt().isAfter(now))
                .findFirst()
                .map(ActionCards::payment);
    }

    private BookingProposal owned(UUID userId, UUID proposalId) {
        // Một mã cho "không có" và "của người khác": không xác nhận đề xuất của người khác có thật.
        return proposals
                .findById(proposalId)
                .filter(p -> p.userId().equals(userId))
                .orElseThrow(() -> new ApiException(AiChatboxErrorCode.PROPOSAL_NOT_FOUND, "Không tìm thấy đề xuất"));
    }

    private static ApiException alreadyConfirmed(BookingProposal proposal) {
        return new ApiException(
                AiChatboxErrorCode.PROPOSAL_ALREADY_CONFIRMED,
                "Đề xuất này đã được xác nhận — đơn " + proposal.order().orderNumber(),
                orderMeta(proposal));
    }

    /** 409 ở confirm và cancel cùng mang đơn cũ: giao diện hiện lại thẻ thanh toán thay vì một lỗi trống. */
    private static Map<String, Object> orderMeta(BookingProposal proposal) {
        BookingProposal.Order o = proposal.order();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", o.orderId());
        order.put("orderNumber", o.orderNumber());
        order.put("totalVnd", proposal.totalVnd());
        order.put("checkoutUrl", o.checkoutUrl());
        order.put("paymentExpiresAt", o.paymentExpiresAt());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("order", order);
        meta.put("action", ActionCardView.of(ActionCards.payment(proposal)));
        return meta;
    }

    private static String announcement(BookingProposal proposal, EventDetail event, PlacedOrder order) {
        String tierName = event.sessions().stream()
                .filter(s -> proposal.eventSessionId().equals(s.id()))
                .flatMap(s -> s.tiers().stream())
                .filter(t -> proposal.zoneCode().equalsIgnoreCase(t.zoneCode()))
                .map(EventDetail.Tier::name)
                .findFirst()
                .orElse(proposal.zoneCode());
        String deadline = order.paymentExpiresAt() == null
                ? ""
                : ", thanh toán trước " + VN_TIME.format(order.paymentExpiresAt());
        return "Đã tạo đơn " + order.orderNumber() + " (" + proposal.quantity() + " vé " + tierName + ", "
                + event.title() + ") — tổng " + formatVnd(proposal.totalVnd()) + deadline + ". Thanh toán tại: "
                + order.checkoutUrl();
    }

    /** "3.600.000đ" — cách khách đọc số tiền. */
    private static String formatVnd(long amount) {
        return String.format(Locale.forLanguageTag("vi-VN"), "%,dđ", amount).replace(',', '.');
    }

    private TicketBookingUseCase requireBooking() {
        if (booking == null || history == null) {
            throw new IllegalStateException(
                    "BookingProposalUseCase dựng ở chế độ chỉ lập đề xuất — không xác nhận được");
        }
        return booking;
    }
}
