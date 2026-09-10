// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort;
import com.nexaticket.aichatbox.domain.port.LlmUnavailableException;
import com.nexaticket.aichatbox.domain.port.SessionNotOwnedException;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import com.nexaticket.platform.web.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent hỗ trợ khách hàng — vòng ReAct.
 *
 * <p>Vòng lặp nằm ở đây chứ không nằm trong adapter LLM, vì những gì nó quyết định đều là quyết
 * định nghiệp vụ: lặp bao nhiêu lần thì dừng, tool nào được phép chạy, hỏng thì nói gì với khách.
 * Giao chúng cho "tool runner" dựng sẵn của SDK nghĩa là không test được nếu không gọi API thật.
 *
 * <h2>Ba điều tuyệt đối không nhường cho mô hình</h2>
 *
 * <ol>
 *   <li><b>Danh tính.</b> {@code userId} đến từ JWT đã xác thực, không bao giờ từ câu chữ của
 *       khách hay từ tham số mô hình sinh ra.
 *   <li><b>Phân quyền.</b> Không có bước nào ở đây kiểm "đơn này có phải của khách không" — việc
 *       đó do ordering-service làm, vì token đi kèm là của chính khách. Xem {@code
 *       OrderingClientPort}.
 *   <li><b>Điểm dừng.</b> {@code maxToolIterations} là chốt chặn cứng. Mỗi vòng là một lần gọi API
 *       có tính tiền, và một mô hình gọi tool lòng vòng sẽ chạy tới khi hết hạn mức.
 * </ol>
 */
@Service
public class CustomerSupportAgentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CustomerSupportAgentUseCase.class);

    private final ChatHistoryPort history;
    private final EmbeddingPort embeddings;
    private final VectorStorePort knowledge;
    private final LlmProviderPort llm;
    private final ToolDispatcher tools;
    private final AgentProperties properties;

    public CustomerSupportAgentUseCase(
            ChatHistoryPort history,
            EmbeddingPort embeddings,
            VectorStorePort knowledge,
            LlmProviderPort llm,
            ToolDispatcher tools,
            AgentProperties properties) {
        this.history = history;
        this.embeddings = embeddings;
        this.knowledge = knowledge;
        this.llm = llm;
        this.tools = tools;
        this.properties = properties;
    }

    /**
     * @param sessionId phiên do client gửi lên — <b>dữ liệu chưa tin được</b>, được kiểm chủ sở hữu
     *     ở bước đầu tiên
     * @param userId lấy từ JWT đã xác thực
     */
    public AgentReply executeAgentProcess(UUID sessionId, UUID userId, String userQuery) {
        // Bước 1 — lịch sử. Đặt trước mọi thứ khác vì nó cũng là bước kiểm quyền: hỏi lịch sử của
        // một phiên không phải của mình thì dừng ngay, trước khi tiêu một đồng nào cho embedding
        // hay cho mô hình.
        List<Exchange> transcript;
        try {
            transcript = new ArrayList<>(history.recentTurns(sessionId, userId, properties.historyTurns()));
        } catch (SessionNotOwnedException e) {
            throw new ApiException(AiChatboxErrorCode.CHAT_SESSION_NOT_FOUND, "Không tìm thấy phiên chat");
        }

        // Bước 2 — RAG.
        List<KnowledgeChunk> context = retrieve(userQuery);

        // Bước 3 — ghép prompt. Ngữ cảnh đi vào MESSAGE, không vào prompt hệ thống: prompt hệ
        // thống là phần được cache giữa mọi request, và một đoạn RAG khác nhau mỗi lượt sẽ phá
        // cache đó mà không để lại dấu vết nào ngoài hoá đơn.
        transcript.add(new Exchange.UserSaid(SupportAgentPrompts.userMessageWithContext(userQuery, context)));

        // Bước 4 — vòng ReAct.
        Set<String> toolsUsed = new LinkedHashSet<>();
        String answer = null;
        for (int round = 0; round < properties.maxToolIterations() && answer == null; round++) {
            LlmProviderPort.LlmTurn turn = ask(transcript);
            switch (turn) {
                case LlmProviderPort.LlmTurn.Answer a -> answer = a.text();
                case LlmProviderPort.LlmTurn.ToolRequest request -> {
                    transcript.add(new Exchange.AssistantRequestedTools(request.calls(), request.providerEcho()));
                    List<ToolOutcome> outcomes = new ArrayList<>(request.calls().size());
                    for (ToolInvocation call : request.calls()) {
                        toolsUsed.add(call.toolName());
                        outcomes.add(tools.dispatch(call));
                    }
                    // MỘT mục cho TẤT CẢ kết quả. Tách ra thì request kế tiếp thiếu kết quả cho
                    // một lời gọi đã có, và mô hình học được rằng gọi song song không được đáp ứng.
                    transcript.add(new Exchange.ToolsReturned(outcomes));
                }
            }
        }

        if (answer == null) {
            // Hết vòng mà chưa có câu trả lời. Trả một câu tử tế thay vì ném lỗi ra màn hình chat:
            // khách không quan tâm agent đã lặp mấy vòng, họ cần biết đi đâu tiếp. Dòng WARN này
            // là thứ để đo — lặp trần thường xuyên nghĩa là mô tả tool đang mơ hồ.
            log.warn(
                    "Agent hết {} vòng mà chưa trả lời (phiên {}), tool đã gọi: {}",
                    properties.maxToolIterations(),
                    sessionId,
                    toolsUsed);
            answer = SupportAgentPrompts.gaveUpMessage();
        }

        // Bước 5 — lưu. Lưu câu hỏi GỐC, không lưu bản đã ghép ngữ cảnh RAG: ngữ cảnh là thứ dựng
        // lại được và khác nhau mỗi lượt, còn lưu nó nghĩa là lượt sau đọc lại ngữ cảnh cũ như thể
        // khách đã nói ra.
        history.append(sessionId, userId, ChatRole.USER, userQuery);
        history.append(sessionId, userId, ChatRole.ASSISTANT, answer);
        return new AgentReply(sessionId, answer, List.copyOf(toolsUsed));
    }

    private LlmProviderPort.LlmTurn ask(List<Exchange> transcript) {
        try {
            return llm.complete(new LlmProviderPort.LlmRequest(
                    SupportAgentPrompts.systemPrompt(), transcript, SupportAgentTools.all()));
        } catch (LlmUnavailableException e) {
            // 503, không phải 500: nhà cung cấp quá tải là chuyện tạm thời và client nên hiện
            // "thử lại" chứ không phải "đã có lỗi xảy ra".
            throw new ApiException(
                    AiChatboxErrorCode.ASSISTANT_UNAVAILABLE, "Trợ lý đang bận, bạn thử lại sau ít phút nhé");
        }
    }

    /**
     * Tìm ngữ cảnh, rồi <b>cắt theo ngưỡng</b>.
     *
     * <p>pgvector luôn trả đủ {@code topK} kết quả kể cả khi kho tri thức không có gì dính tới câu
     * hỏi — "gần nhất" trong một kho toàn nội dung lạc đề vẫn là lạc đề. Không cắt thì những đoạn
     * đó đi thẳng vào prompt và trở thành nguyên liệu để mô hình dựng một câu trả lời nghe hợp lý.
     *
     * <p>Hỏng thì trả rỗng, không ném: mất RAG nghĩa là agent kém thông tin, chứ không phải hỏng.
     * Nó vẫn tra được đơn hàng — thứ mà khách hỏi nhiều nhất.
     */
    private List<KnowledgeChunk> retrieve(String question) {
        try {
            float[] embedding = embeddings.embedQuery(question);
            return knowledge.searchSimilar(embedding, properties.retrievalTopK()).stream()
                    .filter(chunk -> chunk.distance() <= properties.maxRetrievalDistance())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Không lấy được ngữ cảnh RAG, trả lời không kèm tri thức nền", e);
            return List.of();
        }
    }
}
