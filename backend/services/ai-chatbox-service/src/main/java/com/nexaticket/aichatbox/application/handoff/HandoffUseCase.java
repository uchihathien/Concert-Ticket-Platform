// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 */
@Service
public class HandoffUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandoffUseCase.class);

    /** Trần độ dài của câu chụp lại — hàng đợi hiển thị một dòng, không phải cả đoạn văn. */
    private static final int QUESTION_PREVIEW = 500;

    private final HandoffRepository handoffs;
    private final ChatHistoryPort history;
    private final Clock clock;

    public HandoffUseCase(HandoffRepository handoffs, ChatHistoryPort history, Clock clock) {
        this.handoffs = handoffs;
        this.history = history;
        this.clock = clock;
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
        return handoffs.openBySession(sessionId).map(handoff -> HandoffViews.HandoffRow.of(handoff, clock.instant()));
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
     */
    @Transactional
    public HandoffViews.HandoffRow requestByCustomer(UUID sessionId, UUID userId) {
        requireOwnedSession(sessionId, userId);

        Handoff opened =
                escalate(sessionId, userId, HandoffTrigger.CUSTOMER_REQUEST, "Khách bấm nút gặp nhân viên", null);
        return HandoffViews.HandoffRow.of(opened, clock.instant());
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
     */
    @Transactional
    public Handoff escalate(UUID sessionId, UUID userId, HandoffTrigger trigger, String reason, String question) {
        Handoff opened = handoffs.openOrExisting(
                Handoff.request(sessionId, userId, trigger, reason, preview(question), clock.instant()));

        // Ghi ở mức INFO chứ không DEBUG: tỷ lệ chuyển tiếp là chỉ số sức khoẻ của trợ lý, và nó
        // phải đọc được từ log của môi trường chạy thật chứ không chỉ lúc bật gỡ lỗi.
        log.info("Phiên {} chuyển sang người thật ({}): {}", sessionId, trigger, reason);
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

        history.appendTurn(sessionId, userId, userQuery, ChatRole.ASSISTANT, reply);
        return escalate(sessionId, userId, trigger, reason, userQuery);
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
     * Nhận phiếu.
     *
     * <p>Người khác nhận trước thì trả 409, không trả phiếu. Đây là câu trả lời <b>đúng</b> chứ
     * không phải lỗi: màn hình cần biết để gỡ phiếu ấy khỏi danh sách và mở phiếu khác, chứ không
     * phải hiện một cuộc hội thoại mà người trực không được trả lời.
     */
    @Transactional
    public HandoffViews.HandoffRow claim(UUID handoffId, UUID agentId) {
        Optional<Handoff> claimed = handoffs.claim(handoffId, agentId, clock.instant());
        if (claimed.isPresent()) {
            return HandoffViews.HandoffRow.of(claimed.get(), clock.instant());
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
            return HandoffViews.HandoffRow.of(current, clock.instant());
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
            return HandoffViews.HandoffRow.of(handoff, clock.instant());
        }
        if (handoff.assignedAgentId() != null && !agentId.equals(handoff.assignedAgentId())) {
            throw new ApiException(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED, "Phiếu này do người khác phụ trách");
        }
        Handoff resolved = handoff.resolve(clock.instant());
        handoffs.save(resolved);
        return HandoffViews.HandoffRow.of(resolved, clock.instant());
    }

    /**
     * Hàng đợi của người trực, đã ở hình dạng hiển thị.
     *
     * @param mine {@code null} lấy cả hàng đợi; khác {@code null} chỉ lấy phiếu của người ấy
     */
    @Transactional(readOnly = true)
    public List<HandoffViews.HandoffRow> queue(UUID mine, int limit, int offset) {
        Instant now = clock.instant();
        return handoffs.queue(mine, limit, offset).stream()
                .map(handoff -> HandoffViews.HandoffRow.of(handoff, now))
                .toList();
    }

    /** Phiếu kèm cả hội thoại — một lời gọi cho cả màn hình của người trực. */
    @Transactional(readOnly = true)
    public HandoffViews.HandoffThread thread(UUID handoffId, int transcriptLimit) {
        Handoff handoff = require(handoffId);
        return new HandoffViews.HandoffThread(
                HandoffViews.HandoffRow.of(handoff, clock.instant()),
                history.transcriptForSupport(handoff.sessionId(), transcriptLimit).stream()
                        .map(HandoffViews.MessageRow::of)
                        .toList());
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
