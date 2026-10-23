// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingDraft;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.port.BookingDraftRepository;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.domain.port.SeatAvailabilityPort;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Đặt vé trong khung chat bằng chip, không qua mô hình: "cho mình 2 vé đêm nhạc Trịnh" → chọn suất →
 * chọn khu → thẻ đề xuất (Đồng ý/Huỷ) → thẻ thanh toán.
 *
 * <h2>Vì sao không để mô hình dẫn luồng này</h2>
 *
 * <p>Đo trên qwen2.5 7B: khách phải gõ lại câu "lập đề xuất" một hai lần mới ra thẻ, mỗi lượt 30 giây
 * tới 2 phút, và mô hình hay hỏi lại "mã suất" hoặc tự đặt tên khu. Mọi bước ở đây là tra cứu có
 * đáp án duy nhất — sự kiện nào khớp tên, suất nào còn mở bán, khu nào còn chỗ — nên code làm, tức
 * thì và đúng mọi lần. Mô hình vẫn trả lời mọi câu khác, và vẫn lập được đề xuất bằng tool
 * {@code proposeBooking} như trước.
 *
 * <h2>Chip mang dữ liệu, câu chữ chỉ là nhãn</h2>
 *
 * <p>Mỗi chip gửi lại một câu ("Hạng VIP") để hội thoại đọc lại vẫn tự nhiên, nhưng server không đoán
 * ngược câu ấy: nó đối chiếu với đúng danh sách chip vừa hiện ({@link BookingDraft}, V0111), nơi mỗi
 * chip mang sẵn slug, suất, mã khu. Khách gõ tay thì chỉ nhận số vé ("2", "hai vé") và tên hạng vé
 * khớp đúng — còn lại để mô hình trả lời.
 *
 * <h2>Không giữ chỗ, không tạo đơn</h2>
 *
 * <p>Đích của luồng là {@link BookingProposalUseCase#propose} — cùng đề xuất mà tool của mô hình lập.
 * Giữ chỗ và tạo đơn vẫn chỉ xảy ra khi khách bấm Đồng ý (ADR-2001); bấm hai lần vẫn là một đơn, vì
 * khoá chống trùng suy từ id đề xuất.
 */
@Service
public class ChatBookingFlow {

    private static final Logger log = LoggerFactory.getLogger(ChatBookingFlow.class);

    private static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' d/M/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    /** Chip số lượng: 1–4 là đủ cho gần như mọi lần mua qua chat; gõ "5 vé", "6 vé" vẫn được. */
    private static final int QUANTITY_CHIPS = 4;

    private static final int MAX_EVENT_CHOICES = 5;

    private static final String NUMBER = "(\\d+|một|hai|ba|bốn|năm|sáu|bảy|tám|chín|mười)";

    /**
     * "đặt 2 vé …", "mua vé …", "cho mình 2 vé …", "lấy giúp mình 1 vé …". Bắt nhóm 1/2 là số vé.
     *
     * <p>Hẹp có chủ đích: câu hỏi về vé ("vé đứng là gì", "hoàn vé thế nào") không được rơi vào đây.
     */
    private static final Pattern START = Pattern.compile(
            "(?:^|\\s)(?:đặt|mua|lấy|giữ|book)(?:\\s+(?:giúp|hộ|cho)\\s+(?:mình|tôi|em|anh|chị))?(?:\\s+lại)?\\s+(?:"
                    + NUMBER + "\\s+)?vé(?=\\s|$)"
                    + "|(?:^|\\s)cho\\s+(?:mình|tôi|em|anh|chị)\\s+" + NUMBER + "\\s+vé(?=\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern NOT_BOOKING =
            Pattern.compile("hoàn|huỷ|hủy|đổi vé|trả vé|chuyển nhượng|tặng", Pattern.CASE_INSENSITIVE);

    /** "hạng Cự ly 5km của Giải chạy Marathon" → hạng "Cự ly 5km", sự kiện "Giải chạy Marathon". */
    private static final Pattern TIER_OF_EVENT =
            Pattern.compile("^(?:hạng|loại|khu)\\s+(.+?)\\s+(?:của|cho|ở|tại)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEADING_FILLER = Pattern.compile(
            "^(?:(?:của|cho|xem|sự kiện|show|chương trình|buổi|nhé|nha|với|giúp|hộ|mình|tôi)\\s+)+",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_FILLER = Pattern.compile(
            "(?:\\s+(?:nhé|nha|nhá|với|ạ|đi|giúp|hộ|mình|tôi|em|được không|nhe))+$", Pattern.CASE_INSENSITIVE);

    /** "mình thanh toán chưa", "chuyển khoản xong chưa", "đơn của mình sao rồi". */
    private static final Pattern PAYMENT_STATUS = Pattern.compile(
            "(?:thanh toán|chuyển khoản|trả tiền|tiền).*(?:chưa|xong|thành công|được không)"
                    + "|đơn(?: hàng)?(?: của)? (?:mình|tôi|em)(?: đâu)? (?:sao rồi|thế nào|ra sao|đâu rồi)"
                    + "|trạng thái đơn",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CHANGE_SELECTION =
            Pattern.compile("^\\s*đổi lựa chọn\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern BARE_QUANTITY =
            Pattern.compile("^\\s*" + NUMBER + "(?:\\s+vé)?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final CatalogClientPort catalog;
    private final SeatAvailabilityPort availability;
    private final BookingProposalUseCase proposals;
    private final BookingProposalRepository proposalRepository;
    private final BookingDraftRepository drafts;
    private final OrderingClientPort ordering;
    private final CallerCredentialsPort credentials;
    private final Clock clock;

    public ChatBookingFlow(
            CatalogClientPort catalog,
            SeatAvailabilityPort availability,
            BookingProposalUseCase proposals,
            BookingProposalRepository proposalRepository,
            BookingDraftRepository drafts,
            OrderingClientPort ordering,
            CallerCredentialsPort credentials,
            Clock clock) {
        this.catalog = catalog;
        this.availability = availability;
        this.proposals = proposals;
        this.proposalRepository = proposalRepository;
        this.drafts = drafts;
        this.ordering = ordering;
        this.credentials = credentials;
        this.clock = clock;
    }

    /**
     * Một lượt luồng đặt vé: câu trả lời cho khách và thẻ đi kèm.
     *
     * @param card {@code null} khi lượt chỉ có chữ (vd. "bạn muốn đặt vé sự kiện nào?")
     */
    public record Turn(String answer, ActionCard card) {}

    /**
     * Xử lý câu khách nếu nó thuộc luồng đặt vé; {@link Optional#empty()} thì để trợ lý trả lời.
     *
     * <p>Thứ tự: chip vừa hiện (khách bấm) → "đổi lựa chọn" → hỏi trạng thái đơn → câu đặt vé mới →
     * câu gõ tay khớp bước đang chờ.
     */
    public Optional<Turn> handle(UUID chatSessionId, UUID userId, String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<BookingDraft> draft = drafts.find(chatSessionId, userId).filter(d -> d.isFresh(now));

        try {
            if (draft.isPresent()) {
                Optional<BookingDraft.Choice> chosen = draft.get().match(message);
                if (chosen.isPresent()) {
                    return Optional.of(advance(draft.get().apply(chosen.get(), now), now));
                }
            }
            if (CHANGE_SELECTION.matcher(message).matches()) {
                return changeSelection(chatSessionId, userId, now);
            }
            if (PAYMENT_STATUS.matcher(message).find()) {
                Optional<Turn> status = paymentStatus(chatSessionId, userId);
                if (status.isPresent()) {
                    return status;
                }
            }
            Matcher start = START.matcher(message);
            if (start.find() && !NOT_BOOKING.matcher(message).find()) {
                return Optional.of(start(chatSessionId, userId, message, start, now));
            }
            if (draft.isPresent()) {
                return typedAnswer(draft.get(), message, now);
            }
            return Optional.empty();
        } catch (RemoteCallException e) {
            log.warn("Luồng đặt vé trong chat không tra được dữ liệu: {}", e.getMessage());
            return Optional.of(new Turn(
                    "Mình chưa tra được danh mục sự kiện lúc này. Bạn thử lại sau ít phút, hoặc đặt trên trang sự "
                            + "kiện nhé.",
                    null));
        }
    }

    // --- Bắt đầu ---------------------------------------------------------------------------------

    private Turn start(UUID chatSessionId, UUID userId, String message, Matcher start, Instant now) {
        Integer quantity = parseQuantity(start.group(1) != null ? start.group(1) : start.group(2));
        String rest = message.substring(start.end()).strip();
        String tierHint = null;
        Matcher tierOfEvent = TIER_OF_EVENT.matcher(rest);
        if (tierOfEvent.matches()) {
            tierHint = tierOfEvent.group(1).strip();
            rest = tierOfEvent.group(2).strip();
        }
        String query = cleanQuery(rest);

        BookingDraft draft = new BookingDraft(
                chatSessionId, userId, null, null, null, null, null, List.of(), BookingDraft.Step.EVENT, now);
        if (quantity != null && quantity > BookingProposalUseCase.MAX_QUANTITY) {
            draft = draft.asking(BookingDraft.Step.EVENT, null, List.of(), now);
            Turn turn = askEvent(draft, query, tierHint, now);
            return new Turn(
                    "Qua chat mình đặt được tối đa " + BookingProposalUseCase.MAX_QUANTITY
                            + " vé một lần — cần nhiều hơn thì bạn đặt trên trang sự kiện nhé. " + turn.answer(),
                    turn.card());
        }
        if (quantity != null) {
            draft = withQuantity(draft, quantity);
        }
        return askEvent(draft, query, tierHint, now);
    }

    private Turn askEvent(BookingDraft draft, String query, String tierHint, Instant now) {
        if (query.isEmpty()) {
            drafts.save(draft.asking(BookingDraft.Step.EVENT, "Bạn muốn đặt vé sự kiện nào?", List.of(), now));
            return new Turn("Bạn muốn đặt vé sự kiện nào? Gõ tên sự kiện giúp mình nhé.", null);
        }
        List<EventBrief> found = catalog.searchEvents(query, null, null, MAX_EVENT_CHOICES);
        if (found.isEmpty()) {
            drafts.save(draft.asking(BookingDraft.Step.EVENT, "Bạn muốn đặt vé sự kiện nào?", List.of(), now));
            return new Turn(
                    "Mình chưa tìm thấy sự kiện nào đang bán vé khớp với \"" + query
                            + "\". Bạn gõ lại tên sự kiện giúp mình nhé.",
                    null);
        }
        Optional<EventBrief> only = found.size() == 1 ? Optional.of(found.get(0)) : exactTitle(found, query);
        if (only.isPresent()) {
            BookingDraft chosen = withEvent(draft, only.get().slug());
            return tierHint == null ? advance(chosen, now) : advanceWithTierHint(chosen, tierHint, now);
        }
        List<BookingDraft.Choice> choices = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        for (EventBrief event : found) {
            String value = "Sự kiện " + event.title();
            if (!values.add(value)) {
                continue;
            }
            String hint = event.nextSessionAt() == null
                    ? event.city()
                    : VN_TIME.format(event.nextSessionAt()) + (event.city() == null ? "" : " · " + event.city());
            choices.add(new BookingDraft.Choice(event.title(), value, hint, event.slug(), null, null, null));
        }
        String prompt = "Bạn muốn đặt vé sự kiện nào?";
        drafts.save(draft.asking(BookingDraft.Step.EVENT, prompt, choices, now));
        return new Turn(
                "Mình tìm thấy " + choices.size() + " sự kiện khớp với \"" + query + "\". " + prompt,
                ActionCards.choices(draft.asking(BookingDraft.Step.EVENT, prompt, choices, now)));
    }

    // --- Tiến từng bước --------------------------------------------------------------------------

    /** Bản nháp đã có sự kiện: hỏi bước còn thiếu đầu tiên, hoặc lập đề xuất khi đã đủ. */
    private Turn advance(BookingDraft draft, Instant now) {
        if (draft.eventSlug() == null) {
            return askEvent(draft, "", null, now);
        }
        Optional<EventDetail> found = catalog.findEventBySlug(draft.eventSlug());
        if (found.isEmpty()) {
            drafts.delete(draft.chatSessionId(), draft.userId());
            return new Turn("Sự kiện này không còn trong danh mục. Bạn thử sự kiện khác nhé.", null);
        }
        EventDetail event = found.get();
        List<EventDetail.Session> open = openSessions(event, now);
        if (open.isEmpty()) {
            drafts.delete(draft.chatSessionId(), draft.userId());
            return new Turn(event.title() + " hiện không còn suất nào đang mở bán vé.", null);
        }

        Optional<EventDetail.Session> session =
                open.stream().filter(s -> s.id().equals(draft.eventSessionId())).findFirst();
        if (session.isEmpty() && open.size() == 1) {
            session = Optional.of(open.get(0));
        }
        if (session.isEmpty()) {
            return askSession(draft, event, open, now);
        }

        BookingDraft withSession = withSession(draft, session.get().id());
        Map<String, Integer> left = availability.availableByZone(session.get().id());
        List<EventDetail.Tier> buyable = session.get().tiers().stream()
                .filter(t -> left.getOrDefault(t.zoneCode(), 1) > 0)
                .toList();
        if (buyable.isEmpty()) {
            if (open.size() > 1) {
                Turn other = askSession(withSession, event, open, now);
                return new Turn(
                        "Suất " + VN_TIME.format(session.get().startsAt()) + " đã hết vé. " + other.answer(),
                        other.card());
            }
            drafts.delete(draft.chatSessionId(), draft.userId());
            return new Turn(event.title() + " đã bán hết vé.", null);
        }

        Optional<EventDetail.Tier> tier = buyable.stream()
                .filter(t -> t.zoneCode().equalsIgnoreCase(withSession.zoneCode()))
                .findFirst();
        if (tier.isEmpty() && buyable.size() == 1) {
            tier = Optional.of(buyable.get(0));
        }
        if (tier.isEmpty()) {
            return askZone(withSession, event, session.get(), buyable, left, now);
        }

        BookingDraft withZone = withZone(withSession, tier.get().zoneCode());
        if (withZone.quantity() == null) {
            return askQuantity(withZone, event, tier.get(), left.get(tier.get().zoneCode()), now);
        }
        return propose(withZone, event, session.get(), tier.get(), buyable, left, now);
    }

    /** "hạng Cự ly 5km": chọn luôn suất và khu nếu đúng một hạng vé khớp tên. */
    private Turn advanceWithTierHint(BookingDraft draft, String tierHint, Instant now) {
        Optional<EventDetail> event = catalog.findEventBySlug(draft.eventSlug());
        if (event.isPresent()) {
            String hint = fold(tierHint);
            List<Map.Entry<EventDetail.Session, EventDetail.Tier>> matches = new ArrayList<>();
            for (EventDetail.Session session : openSessions(event.get(), now)) {
                for (EventDetail.Tier tier : session.tiers()) {
                    if (fold(tier.name()).equals(hint) || fold(tier.zoneCode()).equals(hint)) {
                        matches.add(Map.entry(session, tier));
                    }
                }
            }
            if (matches.size() == 1) {
                draft = withZone(
                        withSession(draft, matches.get(0).getKey().id()),
                        matches.get(0).getValue().zoneCode());
            }
        }
        return advance(draft, now);
    }

    private Turn askSession(BookingDraft draft, EventDetail event, List<EventDetail.Session> open, Instant now) {
        List<BookingDraft.Choice> choices = new ArrayList<>();
        for (EventDetail.Session session : open) {
            String when = VN_TIME.format(session.startsAt());
            String hint = session.tiers().stream()
                    .map(EventDetail.Tier::priceVnd)
                    .min(Long::compare)
                    .map(price -> "từ " + formatVnd(price))
                    .orElse(null);
            choices.add(new BookingDraft.Choice(when, "Suất " + when, hint, null, session.id(), null, null));
        }
        String prompt = "Bạn muốn xem suất nào?";
        BookingDraft asking = draft.asking(BookingDraft.Step.SESSION, prompt, choices, now);
        drafts.save(asking);
        return new Turn(
                event.title() + " có " + open.size() + " suất đang mở bán. " + prompt, ActionCards.choices(asking));
    }

    private Turn askZone(
            BookingDraft draft,
            EventDetail event,
            EventDetail.Session session,
            List<EventDetail.Tier> buyable,
            Map<String, Integer> left,
            Instant now) {
        List<BookingDraft.Choice> choices = new ArrayList<>();
        for (EventDetail.Tier tier : buyable) {
            Integer seats = left.get(tier.zoneCode());
            String hint = formatVnd(tier.priceVnd()) + (seats == null ? "" : " · còn " + seats + " chỗ");
            choices.add(new BookingDraft.Choice(
                    tier.name(), "Hạng " + tier.name(), hint, null, null, tier.zoneCode(), null));
        }
        String prompt = "Bạn chọn hạng vé nào?";
        BookingDraft asking = draft.asking(BookingDraft.Step.ZONE, prompt, choices, now);
        drafts.save(asking);
        return new Turn(
                event.title() + " · " + VN_TIME.format(session.startsAt()) + ". " + prompt,
                ActionCards.choices(asking));
    }

    private Turn askQuantity(BookingDraft draft, EventDetail event, EventDetail.Tier tier, Integer seats, Instant now) {
        int top = Math.min(QUANTITY_CHIPS, seats == null ? QUANTITY_CHIPS : Math.max(1, seats));
        List<BookingDraft.Choice> choices = new ArrayList<>();
        for (int n = 1; n <= top; n++) {
            choices.add(
                    new BookingDraft.Choice(n + " vé", n + " vé", formatVnd(tier.priceVnd() * n), null, null, null, n));
        }
        String prompt = "Bạn lấy mấy vé?";
        BookingDraft asking = draft.asking(BookingDraft.Step.QUANTITY, prompt, choices, now);
        drafts.save(asking);
        return new Turn(
                "Hạng " + tier.name() + " · " + formatVnd(tier.priceVnd()) + " một vé. " + prompt + " (qua chat tối đa "
                        + BookingProposalUseCase.MAX_QUANTITY + " vé)",
                ActionCards.choices(asking));
    }

    /** Đủ bốn thứ: lập đề xuất. Thẻ đề xuất CHÍNH LÀ bước xác nhận — chưa giữ chỗ nào. */
    private Turn propose(
            BookingDraft draft,
            EventDetail event,
            EventDetail.Session session,
            EventDetail.Tier tier,
            List<EventDetail.Tier> buyable,
            Map<String, Integer> left,
            Instant now) {
        BookingProposalUseCase.ProposalOutcome outcome = proposals.propose(
                draft.userId(), draft.chatSessionId(), event.slug(), session.id(), tier.zoneCode(), draft.quantity());
        return switch (outcome) {
            case BookingProposalUseCase.ProposalOutcome.Proposed proposed -> {
                drafts.delete(draft.chatSessionId(), draft.userId());
                BookingProposal p = proposed.proposal();
                yield new Turn(
                        "Mình đã chuẩn bị đề xuất: " + p.quantity() + " vé " + tier.name() + " · " + event.title()
                                + " · " + VN_TIME.format(session.startsAt()) + " — tạm tính "
                                + formatVnd(p.totalVnd()) + ". Bấm Đồng ý để mình giữ chỗ và tạo đơn; muốn đổi "
                                + "thì bấm Đổi lựa chọn.",
                        ActionCards.proposal(p, event));
            }
            case BookingProposalUseCase.ProposalOutcome.Rejected rejected -> {
                // Không lập được (đã có đề xuất mở cho khu này, khu vừa hết…): nói lý do và cho chọn lại
                // khu — vẫn còn sự kiện, suất và số vé khách đã chọn.
                Turn again = askZone(withZone(draft, null), event, session, buyable, left, now);
                yield new Turn(friendly(rejected.reason()) + " " + again.answer(), again.card());
            }
        };
    }

    /** Khách gõ tay thay vì bấm chip: chỉ nhận số vé, tên hạng vé khớp đúng, hoặc tên sự kiện khi đang hỏi. */
    private Optional<Turn> typedAnswer(BookingDraft draft, String message, Instant now) {
        switch (draft.awaiting()) {
            case QUANTITY -> {
                Matcher bare = BARE_QUANTITY.matcher(message);
                if (bare.matches()) {
                    Integer n = parseQuantity(bare.group(1));
                    if (n != null && n > BookingProposalUseCase.MAX_QUANTITY) {
                        return Optional.of(new Turn(
                                "Qua chat mình đặt được tối đa " + BookingProposalUseCase.MAX_QUANTITY
                                        + " vé một lần. Bạn chọn lại số vé nhé.",
                                ActionCards.choices(draft)));
                    }
                    if (n != null) {
                        return Optional.of(advance(withQuantity(draft, n), now));
                    }
                }
            }
            case ZONE -> {
                String said = fold(message.replaceFirst("(?i)^\\s*(hạng|khu|loại)\\s+", ""));
                Optional<BookingDraft.Choice> zone = draft.choices().stream()
                        .filter(c -> fold(c.label()).equals(said))
                        .findFirst();
                if (zone.isPresent()) {
                    return Optional.of(advance(draft.apply(zone.get(), now), now));
                }
            }
            case EVENT -> {
                if (draft.choices().isEmpty() && message.length() <= 80 && !message.contains("?")) {
                    return Optional.of(askEvent(draft, cleanQuery(message), null, now));
                }
            }
            default -> {
                // SESSION: giờ diễn gõ tay quá nhiều cách viết — để chip làm việc đó.
            }
        }
        return Optional.empty();
    }

    // --- Đổi lựa chọn, trạng thái đơn ------------------------------------------------------------

    /** Nút "Đổi lựa chọn" trên thẻ đề xuất: huỷ đề xuất đang mở, hỏi lại khu (giữ sự kiện, suất, số vé). */
    private Optional<Turn> changeSelection(UUID chatSessionId, UUID userId, Instant now) {
        Optional<BookingProposal> latest = proposalRepository.recentForSession(chatSessionId, userId, 1).stream()
                .findFirst();
        if (latest.isEmpty() || latest.get().status() == BookingProposalStatus.CONFIRMED) {
            return Optional.empty();
        }
        BookingProposal p = latest.get();
        if (p.status() == BookingProposalStatus.PROPOSED) {
            proposals.cancel(userId, p.id());
        }
        BookingDraft draft = new BookingDraft(
                chatSessionId,
                userId,
                p.eventSlug(),
                p.eventSessionId(),
                null,
                p.quantity(),
                null,
                List.of(),
                BookingDraft.Step.ZONE,
                now);
        return Optional.of(advance(draft, now));
    }

    /**
     * "Mình thanh toán chưa": tra đơn vừa tạo trong phiên này và trả lại thẻ đơn với trạng thái mới.
     * Phiên chưa tạo đơn nào thì để trợ lý trả lời (nó tra được mọi đơn của khách bằng tool).
     */
    private Optional<Turn> paymentStatus(UUID chatSessionId, UUID userId) {
        Optional<BookingProposal> confirmed = proposalRepository.recentForSession(chatSessionId, userId, 5).stream()
                .filter(p -> p.status() == BookingProposalStatus.CONFIRMED && p.order() != null)
                .findFirst();
        if (confirmed.isEmpty()) {
            return Optional.empty();
        }
        BookingProposal p = confirmed.get();
        ActionCard.Payment card = proposals.paymentWithEvent(p);
        OrderSummary order;
        try {
            order = ordering.fetchOrder(p.order().orderId(), credentials.currentAccessToken());
        } catch (OrderNotFoundException e) {
            return Optional.empty();
        } catch (RemoteCallException e) {
            // Vẫn trả thẻ (nó tự hỏi lại đơn ở phía giao diện); chỉ câu trả lời là chưa chắc.
            log.warn("Không tra được trạng thái đơn {}: {}", p.order().orderNumber(), e.getMessage());
            return Optional.of(new Turn(
                    "Mình chưa tra được trạng thái đơn " + p.order().orderNumber()
                            + " lúc này — thẻ bên dưới sẽ tự cập nhật khi tiền về.",
                    card));
        }
        String number = p.order().orderNumber();
        String answer =
                switch (order.status()) {
                    case "PAID" -> "Đơn " + number + " đã thanh toán — vé đã có trong mục Vé của tôi.";
                    case "AWAITING_PAYMENT" -> "Đơn " + number + " chưa nhận được thanh toán"
                            + (order.paymentExpiresAt() == null
                                    ? ""
                                    : ", hạn tới " + VN_TIME.format(order.paymentExpiresAt()))
                            + ". Tiền về thì thẻ bên dưới tự chuyển sang Đã thanh toán.";
                    case "EXPIRED" -> "Đơn " + number
                            + " đã quá hạn thanh toán nên chỗ đã được mở lại. Bạn đặt lại nhé.";
                    case "CANCELLED" -> "Đơn " + number + " đã được huỷ.";
                    case "MANUAL_REVIEW" -> "Đơn " + number
                            + " đang được đối soát thanh toán — nhân viên sẽ liên hệ bạn.";
                    default -> "Đơn " + number + " đang ở trạng thái " + order.status() + ".";
                };
        return Optional.of(new Turn(answer, card.withStatus(order.status())));
    }

    // --- Tiện ích --------------------------------------------------------------------------------

    private static List<EventDetail.Session> openSessions(EventDetail event, Instant now) {
        return event.sessions().stream()
                .filter(s -> s.startsAt() != null && s.startsAt().isAfter(now))
                .filter(s -> s.salesCloseAt() == null || s.salesCloseAt().isAfter(now))
                .filter(s -> !s.tiers().isEmpty())
                .sorted(Comparator.comparing(EventDetail.Session::startsAt))
                .toList();
    }

    /** Nhiều kết quả nhưng đúng một cái trùng tên với câu khách gõ (không dấu, không hoa thường). */
    private static Optional<EventBrief> exactTitle(List<EventBrief> found, String query) {
        String q = fold(query);
        List<EventBrief> exact =
                found.stream().filter(e -> fold(e.title()).equals(q)).toList();
        return exact.size() == 1 ? Optional.of(exact.get(0)) : Optional.empty();
    }

    private static String cleanQuery(String text) {
        String q = text.replaceAll("[\"“”?!.]", " ").strip();
        q = LEADING_FILLER.matcher(q).replaceFirst("");
        q = TRAILING_FILLER.matcher(q).replaceFirst("");
        return q.replaceAll("\\s+", " ").strip();
    }

    static Integer parseQuantity(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "một" -> 1;
            case "hai" -> 2;
            case "ba" -> 3;
            case "bốn" -> 4;
            case "năm" -> 5;
            case "sáu" -> 6;
            case "bảy" -> 7;
            case "tám" -> 8;
            case "chín" -> 9;
            case "mười" -> 10;
            default -> {
                try {
                    int n = Integer.parseInt(word);
                    yield n >= 1 ? n : null;
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    /** Lý do từ chối của use case viết cho mô hình đọc ("mời khách…"); đổi sang câu nói với khách. */
    private static String friendly(String reason) {
        if (reason.startsWith("Đã có một đề xuất đang mở")) {
            return "Bạn đang có một đề xuất mở cho hạng vé này — bấm Đồng ý hoặc Huỷ trên thẻ đó trước nhé.";
        }
        if (reason.startsWith("Sơ đồ sự kiện không có khu")) {
            return "Hạng vé này chưa đặt được qua chat — bạn chọn hạng khác, hoặc đặt trên trang sự kiện.";
        }
        return "Mình chưa lập được đề xuất cho lựa chọn này.";
    }

    private static String fold(String text) {
        return Normalizer.normalize(text == null ? "" : text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replaceAll("\\s+", " ");
    }

    /** "3.600.000đ" — cách khách đọc số tiền. */
    private static String formatVnd(long amount) {
        return String.format(Locale.forLanguageTag("vi-VN"), "%,dđ", amount).replace(',', '.');
    }

    private static BookingDraft withEvent(BookingDraft d, String slug) {
        return d.apply(new BookingDraft.Choice("", "", null, slug, null, null, null), d.updatedAt());
    }

    private static BookingDraft withSession(BookingDraft d, UUID sessionId) {
        return d.apply(new BookingDraft.Choice("", "", null, null, sessionId, null, null), d.updatedAt());
    }

    private static BookingDraft withZone(BookingDraft d, String zoneCode) {
        if (zoneCode == null) {
            return new BookingDraft(
                    d.chatSessionId(),
                    d.userId(),
                    d.eventSlug(),
                    d.eventSessionId(),
                    null,
                    d.quantity(),
                    d.prompt(),
                    d.choices(),
                    d.awaiting(),
                    d.updatedAt());
        }
        return d.apply(new BookingDraft.Choice("", "", null, null, null, zoneCode, null), d.updatedAt());
    }

    private static BookingDraft withQuantity(BookingDraft d, int quantity) {
        return d.apply(new BookingDraft.Choice("", "", null, null, null, null, quantity), d.updatedAt());
    }
}
