// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.IncidentKind;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.domain.port.IdentityLookupPort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chuyển cuộc chat sang người thật, và đường đi của người thật.
 *
 * <h2>Phiếu mở là công tắc tắt trợ lý</h2>
 *
 * <p>Khi một phiên có phiếu đang mở, {@code CustomerSupportAgentUseCase} <b>không gọi mô hình</b>
 * nữa — nó chỉ ghi lời khách vào hội thoại rồi trả một câu báo đang chờ người trực. Đây là phần
 * quan trọng nhất của cả tính năng: thiếu nó thì trợ lý vẫn trả lời xen vào giữa, khách nhận hai
 * câu trả lời khác nhau cho cùng một câu hỏi, và không biết tin câu nào.
 *
 * <h2>Chuyển tiếp không làm mất lượt của khách</h2>
 *
 * <p>Câu hỏi khiến khách phải nhờ người vẫn được ghi vào hội thoại và chụp lại vào
 * {@code lastQuestion}. Không làm vậy thì người trực nhận một phiếu không có nội dung và phải hỏi
 * lại "anh/chị cần gì ạ" — đúng câu mà khách vừa gõ xong.
 *
 * <h2>Mỗi phiếu có một ý định</h2>
 *
 * <p>Phiếu do tool mở ({@code requestTicketRefund}, {@code reportIncident}) mang sẵn nhãn vì hành
 * động đã nói rõ khách cần gì. Phiếu còn lại — khách bấm nút, gõ "cho tôi gặp người", trợ lý bỏ
 * cuộc — được gắn nhãn bằng {@link SupportIntentClassifier} từ câu hỏi đang treo. Hai đường, một
 * cột, để hàng đợi lọc được theo một tiêu chí duy nhất.
 */
@Service
public class HandoffUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandoffUseCase.class);

    /** Trần độ dài của câu chụp lại — hàng đợi hiển thị một dòng, không phải cả đoạn văn. */
    private static final int QUESTION_PREVIEW = 500;

    private final HandoffRepository handoffs;
    private final ChatHistoryPort history;
    private final IdentityLookupPort identities;
    private final Clock clock;
    private final AgentMetrics metrics;

    public HandoffUseCase(
            HandoffRepository handoffs,
            ChatHistoryPort history,
            IdentityLookupPort identities,
            Clock clock,
            AgentMetrics metrics) {
        this.handoffs = handoffs;
        this.history = history;
        this.identities = identities;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Phiếu đang mở của một phiên, nếu có. Đây là thứ agent hỏi trước khi gọi mô hình.
     *
     * <p>Trả kiểu domain vì người gọi là {@code CustomerSupportAgentUseCase} — cùng tầng
     * application, và nó cần biết phiếu <b>có hay không</b> chứ không cần hiển thị gì.
     */
    @Transactional(readOnly = true)
    public Optional<Handoff> openFor(UUID sessionId) {
        return handoffs.openBySession(sessionId);
    }

    /** Bản để hiển thị của phiếu đang mở. Tầng interfaces không được chạm vào {@link Handoff}. */
    @Transactional(readOnly = true)
    public Optional<HandoffViews.HandoffRow> openRowFor(UUID sessionId) {
        return handoffs.openBySession(sessionId).map(this::rowOf);
    }

    /**
     * Khách tự bấm nút "gặp nhân viên".
     *
     * <p>Tồn tại để tầng interfaces không phải gọi tên {@link HandoffTrigger} — đó là kiểu của
     * domain, và controller không được chạm vào nó (ArchitectureRules.hexagonalLayers). Lý do
     * chuyển tiếp là một quyết định nghiệp vụ, và nó thuộc về đây.
     *
     * <p><b>Kiểm chủ sở hữu ở đây, không tin {@code sessionId}.</b> Nó đến từ đường dẫn URL, tức là
     * từ client. Không kiểm thì ai đoán ra một UUID phiên cũng mở được phiếu trên hội thoại của
     * người khác, và hậu quả không hề nhẹ: phiếu mở là công tắc tắt trợ lý, nên trợ lý của người đó
     * im ngay; hội thoại của họ vào hàng đợi bàn hỗ trợ; và {@code chat_handoffs.user_id} ghi tên
     * người gọi chứ không phải chủ phiên. Hai đường đọc ({@code recentTurns}, {@code transcript})
     * đã kiểm từ đầu — đường ghi này thì chưa.
     *
     * <p>Ý định lấy từ câu cuối khách đã gõ, nếu có: bấm nút ngay sau khi hỏi "hoàn vé thế nào" là
     * một phiếu hoàn vé, và người trực chuyên mảng ấy nên thấy nó.
     */
    @Transactional
    public HandoffViews.HandoffRow requestByCustomer(UUID sessionId, UUID userId) {
        requireOwnedSession(sessionId, userId);

        String lastUserMessage = history.transcript(sessionId, userId, 20).stream()
                .filter(message -> message.role() == ChatRole.USER)
                .reduce((first, second) -> second)
                .map(message -> message.content())
                .orElse(null);
        Handoff opened = escalate(
                sessionId,
                userId,
                HandoffTrigger.CUSTOMER_REQUEST,
                SupportIntentClassifier.classify(lastUserMessage),
                "Khách bấm nút gặp nhân viên",
                null,
                lastUserMessage);
        return rowOf(opened);
    }

    /**
     * Mở phiếu, gắn nhãn ý định từ câu hỏi.
     *
     * <p>Giữ chữ ký cũ cho hai đường không mang sẵn nhãn — khách gõ "cho tôi gặp người" và trợ lý
     * hết vòng. Đường có nhãn đi qua bản đầy đủ bên dưới.
     */
    @Transactional
    public Handoff escalate(UUID sessionId, UUID userId, HandoffTrigger trigger, String reason, String question) {
        return escalate(sessionId, userId, trigger, SupportIntentClassifier.classify(question), reason, null, question);
    }

    /**
     * Mở phiếu.
     *
     * <p>Idempotent theo phiên: gọi lại khi đã có phiếu mở thì trả lại chính phiếu ấy, không tạo
     * phiếu thứ hai. Khách bấm "gặp nhân viên" ba lần là chuyện thường.
     *
     * <p><b>Phiên phải tồn tại trước.</b> {@code chat_handoffs.session_id} là khoá ngoại trỏ tới
     * {@code chat_sessions}, và dòng đó chỉ ra đời khi có tin nhắn đầu tiên. Người gọi từ ngoài
     * phải bảo đảm điều đó — đường của khách bằng {@link #requireOwnedSession}, đường của trợ lý
     * bằng {@link #escalateWithTurn}, vốn ghi lượt chat trước khi mở phiếu.
     *
     * @param details JSON có cấu trúc cho người trực, hoặc {@code null}
     */
    @Transactional
    public Handoff escalate(
            UUID sessionId,
            UUID userId,
            HandoffTrigger trigger,
            SupportIntent intent,
            String reason,
            String details,
            String question) {
        Instant now = clock.instant();
        Handoff opened = handoffs.openOrExisting(
                Handoff.request(sessionId, userId, trigger, intent, reason, details, preview(question), now));

        // Ghi ở mức INFO chứ không DEBUG: tỷ lệ chuyển tiếp là chỉ số sức khoẻ của trợ lý, và nó
        // phải đọc được từ log của môi trường chạy thật chứ không chỉ lúc bật gỡ lỗi.
        log.info("Phiên {} chuyển sang người thật ({}, {}): {}", sessionId, trigger, opened.intent(), reason);
        // Đếm phiếu MỞ, không đếm lần gọi: openOrExisting là idempotent, và một khách bấm nút ba
        // lần không phải ba lần trợ lý thất bại.
        if (opened.requestedAt().equals(now)) {
            metrics.recordHandoff(trigger.name(), opened.intent().name());
        }
        return opened;
    }

    /**
     * Mở phiếu <b>và</b> ghi trọn lượt chat gây ra nó — một transaction.
     *
     * <p>Đây là đường của trợ lý, và thứ tự bên trong là bắt buộc chứ không phải sở thích: lượt chat
     * được ghi TRƯỚC, vì chính nó tạo ra dòng {@code chat_sessions} mà phiếu trỏ tới bằng khoá
     * ngoại. Mở phiếu trước thì lượt ĐẦU TIÊN của một cuộc trò chuyện — khách vừa mở trang và gõ
     * ngay "cho tôi gặp nhân viên", hoặc mô hình gọi tool chuyển tiếp ở lượt đầu — vỡ ở khoá ngoại
     * và khách nhận 500 thay vì một phiếu.
     *
     * <p>Và một transaction cho cả bốn lệnh ghi, không phải ba transaction rời: phiếu không có tin
     * nhắn nào là một phiếu người trực mở ra rồi phải hỏi "anh/chị cần gì ạ".
     */
    @Transactional
    public Handoff escalateWithTurn(
            UUID sessionId, UUID userId, HandoffTrigger trigger, String reason, String userQuery, String reply) {
        return escalateWithTurn(
                sessionId, userId, trigger, SupportIntentClassifier.classify(userQuery), reason, null, userQuery, reply);
    }

    /** Bản có nhãn và dữ liệu kèm — đường của các tool mở phiếu theo mục đích cụ thể. */
    @Transactional
    public Handoff escalateWithTurn(
            UUID sessionId,
            UUID userId,
            HandoffTrigger trigger,
            SupportIntent intent,
            String reason,
            String details,
            String userQuery,
            String reply) {

        history.appendTurn(sessionId, userId, userQuery, ChatRole.ASSISTANT, reply);
        return escalate(sessionId, userId, trigger, intent, reason, details, userQuery);
    }

    private void requireOwnedSession(UUID sessionId, UUID userId) {
        // Cùng một mã lỗi cho "không có phiên" và "phiên của người khác" — cố ý không phân biệt,
        // vì phân biệt được nghĩa là dò ra được UUID phiên nào có thật.
        if (history.ownerOf(sessionId).filter(userId::equals).isEmpty()) {
            throw new ApiException(AiChatboxErrorCode.CHAT_SESSION_NOT_FOUND, "Không tìm thấy phiên chat");
        }
    }

    // --- Đường của người trực --------------------------------------------

    /**
     * Nhận phiếu — và nhận về <b>cả hội thoại</b>.
     *
     * <p>Trả về thread chứ không trả về một dòng: người trực vừa bấm Nhận là người sắp phải trả
     * lời, và thứ họ cần ngay là toàn bộ những gì khách (USER), trợ lý (ASSISTANT) và người trực
     * trước đó (AGENT) đã nói. Trả về dòng rồi bắt gọi thêm một request là một vòng khứ hồi nữa
     * đứng giữa "nhận" và "đọc".
     *
     * <p>Người khác nhận trước thì trả 409, không trả phiếu. Đây là câu trả lời <b>đúng</b> chứ
     * không phải lỗi: màn hình cần biết để gỡ phiếu ấy khỏi danh sách và mở phiếu khác, chứ không
     * phải hiện một cuộc hội thoại mà người trực không được trả lời.
     */
    @Transactional
    public HandoffViews.HandoffThread claim(UUID handoffId, UUID agentId, int transcriptLimit) {
        Optional<Handoff> claimed = handoffs.claim(handoffId, agentId, clock.instant());
        if (claimed.isPresent()) {
            return threadOf(claimed.get(), transcriptLimit);
        }

        // Không nhận được KHÔNG đồng nghĩa với "người khác nhận trước". Ba tình huống khác nhau, và
        // hai trong số đó không phải lỗi của người đang bấm.
        Handoff current = require(handoffId);

        if (!current.isOpen()) {
            throw new ApiException(AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED, "Phiếu này đã được đóng");
        }
        if (agentId.equals(current.assignedAgentId())) {
            // Chính mình đang giữ phiếu. F5 hoặc bấm hai lần là chuyện xảy ra hàng ngày, và trả 409
            // cho nó nghĩa là màn hình gỡ phiếu khỏi danh sách rồi hiện "người khác đã nhận" về
            // một phiếu mà người dùng đang trả lời. Nhận phiếu là idempotent với chính người nhận.
            return threadOf(current, transcriptLimit);
        }
        throw new ApiException(AiChatboxErrorCode.HANDOFF_ALREADY_TAKEN, "Phiếu này vừa được người khác nhận");
    }

    /**
     * Người trực trả lời khách.
     *
     * <p>Phải nhận phiếu trước. Không có điều kiện đó thì ai cũng nói được vào bất cứ cuộc hội
     * thoại nào, và {@code assignedAgentId} trở thành một cột trang trí.
     */
    @Transactional
    public void reply(UUID handoffId, UUID agentId, String text) {
        Handoff handoff = require(handoffId);

        // Phiếu đã đóng thì trợ lý AI đã trả lời trở lại từ lượt kế tiếp. Thêm một câu của người
        // trực vào lúc này là dựng lại đúng cảnh mà cả tính năng này tồn tại để tránh: hai phía
        // cùng trả lời một cuộc hội thoại, và khách không biết tin ai.
        if (!handoff.isOpen()) {
            throw new ApiException(
                    AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED, "Phiếu này đã đóng — hãy mở lại phiếu mới nếu cần");
        }
        if (!agentId.equals(handoff.assignedAgentId())) {
            throw new ApiException(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED, "Hãy nhận phiếu này trước khi trả lời");
        }
        // Ghi dưới vai chủ sở hữu phiên, không phải vai người trực: cột `user_id` của
        // `chat_messages` nói hội thoại này THUỘC VỀ AI, không nói ai vừa gõ. Ghi id người trực vào
        // đó sẽ làm chính cuộc hội thoại ấy biến mất khỏi màn hình của khách.
        history.append(handoff.sessionId(), handoff.userId(), ChatRole.AGENT, text);
    }

    /** Đóng phiếu. Từ lượt kế tiếp trợ lý AI trả lời trở lại. */
    @Transactional
    public HandoffViews.HandoffRow resolve(UUID handoffId, UUID agentId) {
        Handoff handoff = require(handoffId);

        // Đóng một phiếu đã đóng thì trả lại chính nó, không ghi lại `resolved_at`: bấm hai lần
        // không được phép dịch mốc thời gian mà báo cáo "xử lý mất bao lâu" đang đọc.
        if (!handoff.isOpen()) {
            return rowOf(handoff);
        }
        if (handoff.assignedAgentId() != null && !agentId.equals(handoff.assignedAgentId())) {
            throw new ApiException(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED, "Phiếu này do người khác phụ trách");
        }
        Handoff resolved = handoff.resolve(clock.instant());
        handoffs.save(resolved);
        return rowOf(resolved);
    }

    /**
     * Hàng đợi của người trực, đã ở hình dạng hiển thị.
     *
     * @param mine {@code null} lấy cả hàng đợi; khác {@code null} chỉ lấy phiếu của người ấy
     * @param intent tên ý định (chuỗi, vì bên gọi là tầng interfaces); {@code null} hoặc không
     *     hiểu được thì không lọc
     */
    @Transactional(readOnly = true)
    public List<HandoffViews.HandoffRow> queue(UUID mine, String intent, int limit, int offset) {
        return withAgentNames(handoffs.queue(mine, intentFilter(intent), limit, offset));
    }

    /**
     * Tra phiếu cho màn lịch sử hỗ trợ.
     *
     * @param status {@code OPEN} · {@code WAITING} · {@code ASSIGNED} · {@code RESOLVED}, hoặc
     *     {@code null} cho mọi trạng thái. Nhận CHUỖI vì bên gọi là tầng interfaces, nơi không được
     *     chạm vào {@code HandoffStatus} — và vì "phiếu chưa xong gồm những trạng thái nào" là quyết
     *     định nghiệp vụ, thuộc về đây chứ không thuộc về một ô chọn trên giao diện.
     * @param intent tên ý định, cùng quy ước
     * @param query tìm trong lý do chuyển và câu hỏi cuối của khách
     */
    @Transactional(readOnly = true)
    public List<HandoffViews.HandoffRow> search(
            UUID mine, String status, String intent, String query, int limit, int offset) {
        return search(mine, statusFilter(status), intentFilter(intent), query, limit, offset);
    }

    /**
     * Tên trạng thái thành tập trạng thái.
     *
     * <p>Tên không hiểu được thì trả tập rỗng, nghĩa là không lọc. Ném lỗi cũng hợp lý, nhưng một ô
     * chọn gửi sai giá trị là lỗi của giao diện, và đáp lại bằng danh sách đầy đủ hữu ích hơn một màn
     * hình lỗi.
     */
    private static Set<HandoffStatus> statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return Set.of();
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "OPEN" -> Set.of(HandoffStatus.WAITING, HandoffStatus.ASSIGNED);
            case "WAITING" -> Set.of(HandoffStatus.WAITING);
            case "ASSIGNED" -> Set.of(HandoffStatus.ASSIGNED);
            case "RESOLVED" -> Set.of(HandoffStatus.RESOLVED);
            default -> Set.of();
        };
    }

    /** Cùng triết lý với {@link #statusFilter}: tên lạ nghĩa là không lọc, không phải lỗi. */
    private static SupportIntent intentFilter(String intent) {
        if (intent == null || intent.isBlank()) {
            return null;
        }
        try {
            return SupportIntent.valueOf(intent.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<HandoffViews.HandoffRow> search(
            UUID mine, Set<HandoffStatus> statuses, SupportIntent intent, String query, int limit, int offset) {
        // Mới nhất trước, ngược với hàng đợi. Hàng đợi là danh sách việc phải làm nên phiếu chờ lâu
        // nhất đứng đầu; bảng tra cứu là để xem việc vừa xảy ra.
        return withAgentNames(handoffs.search(mine, statuses, intent, query, true, limit, offset));
    }

    /**
     * Một phiếu, kèm tên người đang cầm nó.
     *
     * <p>Chỉ gọi identity khi phiếu ĐÃ có người nhận. Phiếu còn trong hàng đợi thì không có ai để
     * tra, và một lời gọi HTTP để nhận về rỗng nằm ngay trong đường đi của khách là lời gọi tệ nhất
     * trong hệ thống này.
     */
    private HandoffViews.HandoffRow rowOf(Handoff handoff) {
        String agentName = handoff.assignedAgentId() == null
                ? null
                : identities.displayNameOf(handoff.assignedAgentId()).orElse(null);
        return HandoffViews.HandoffRow.of(handoff, clock.instant(), agentName);
    }

    /**
     * Gắn tên người trực vào từng dòng, tra MỘT LẦN cho mỗi người.
     *
     * <p>Một hàng đợi 50 phiếu thường do vài người cầm, nên gộp theo id biến 50 lời gọi HTTP thành
     * hai hoặc ba. Không gộp thì mỗi lần mở màn hình là một cơn mưa request sang identity-service,
     * và người trực chờ chính cơn mưa ấy.
     */
    private List<HandoffViews.HandoffRow> withAgentNames(List<Handoff> rows) {
        Instant now = clock.instant();
        Map<UUID, String> names = new HashMap<>();
        for (Handoff handoff : rows) {
            UUID agentId = handoff.assignedAgentId();
            if (agentId != null && !names.containsKey(agentId)) {
                // `put` kể cả khi rỗng: nhớ luôn cả lần tra không ra, để một id không tra được không
                // bị hỏi lại cho từng dòng còn lại trong cùng danh sách.
                names.put(agentId, identities.displayNameOf(agentId).orElse(null));
            }
        }
        return rows.stream()
                .map(handoff -> HandoffViews.HandoffRow.of(handoff, now, names.get(handoff.assignedAgentId())))
                .toList();
    }

    /** Phiếu kèm cả hội thoại — một lời gọi cho cả màn hình của người trực. */
    @Transactional(readOnly = true)
    public HandoffViews.HandoffThread thread(UUID handoffId, int transcriptLimit) {
        return threadOf(require(handoffId), transcriptLimit);
    }

    private HandoffViews.HandoffThread threadOf(Handoff handoff, int transcriptLimit) {
        return new HandoffViews.HandoffThread(
                rowOf(handoff),
                history.transcriptForSupport(handoff.sessionId(), transcriptLimit).stream()
                        .map(HandoffViews.MessageRow::of)
                        .toList(),
                checklistFor(handoff));
    }

    /**
     * Danh sách việc theo khung mẫu — chỉ với phiếu sự cố.
     *
     * <p>Loại sự cố nằm trong {@code details} (JSON do {@code SupportCaseUseCase} dựng). Đọc bằng
     * một phép tìm chuỗi đơn giản thay vì parse JSON: {@code details} là dữ liệu hiển thị, không
     * có schema, và nếu nó không đúng dạng thì câu trả lời đúng là "không có checklist" chứ không
     * phải 500 cho cả màn hình.
     */
    private static List<String> checklistFor(Handoff handoff) {
        if (handoff.intent() != SupportIntent.INCIDENT || handoff.details() == null) {
            return List.of();
        }
        for (IncidentKind kind : IncidentKind.values()) {
            if (handoff.details().contains("\"" + kind.name() + "\"")) {
                return IncidentTemplates.forKind(kind).checklist();
            }
        }
        return List.of();
    }

    /** Hội thoại của chính khách, kèm phiếu đang mở nếu có. */
    @Transactional(readOnly = true)
    public HandoffViews.ChatThread customerThread(UUID sessionId, UUID userId, int transcriptLimit) {
        return new HandoffViews.ChatThread(
                sessionId,
                openRowFor(sessionId).orElse(null),
                history.transcript(sessionId, userId, transcriptLimit).stream()
                        .map(HandoffViews.MessageRow::of)
                        .toList());
    }

    private Handoff require(UUID handoffId) {
        return handoffs.findById(handoffId)
                .orElseThrow(() -> new ApiException(AiChatboxErrorCode.HANDOFF_NOT_FOUND, "Không tìm thấy phiếu"));
    }

    /** Cắt cứng, không cắt theo từ: đây là bản xem trước, không phải nội dung cần đọc trọn. */
    private static String preview(String question) {
        if (question == null) {
            return null;
        }
        String trimmed = question.strip();
        return trimmed.length() <= QUESTION_PREVIEW ? trimmed : trimmed.substring(0, QUESTION_PREVIEW) + "…";
    }
}
