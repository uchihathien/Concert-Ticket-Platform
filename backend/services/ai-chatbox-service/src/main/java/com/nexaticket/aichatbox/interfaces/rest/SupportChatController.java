// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.TurnAdmission;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent hỗ trợ khách hàng. */
@RestController
@RequestMapping("/v1/chat/agent")
public class SupportChatController {

    /** Đủ dài cho một cuộc hỗ trợ, không tải cả lịch sử của một người dùng lâu năm. */
    private static final int TRANSCRIPT_LIMIT = 200;

    private final CustomerSupportAgentUseCase agent;
    private final HandoffUseCase handoffs;
    private final TurnAdmission admission;
    private final Executor agentExecutor;
    private final ChatIdempotency idempotency;

    public SupportChatController(
            CustomerSupportAgentUseCase agent,
            HandoffUseCase handoffs,
            TurnAdmission admission,
            @Qualifier("agentExecutor") Executor agentExecutor,
            ChatIdempotency idempotency) {
        this.agent = agent;
        this.handoffs = handoffs;
        this.admission = admission;
        this.agentExecutor = agentExecutor;
        this.idempotency = idempotency;
    }

    /**
     * @param sessionId phiên hiện có; bỏ trống thì mở phiên mới. Client gửi lên nên đây là dữ liệu
     *     chưa tin được — quyền sở hữu được kiểm ở tầng dưới, không ở đây.
     * @param message giới hạn độ dài là chốt chặn chi phí, không phải chốt chặn kỹ thuật: token
     *     đầu vào tính tiền, và không có trần thì một lần dán nhầm cả cuốn sách là một hoá đơn.
     */
    public record AskRequest(UUID sessionId, @NotBlank @Size(max = 2000) String message) {}

    public record AskResponse(UUID sessionId, String answer, List<String> toolsUsed) {}

    /**
     * Lượt chat — <b>chạy trên pool riêng, không giữ luồng của Tomcat</b>.
     *
     * <p>Một lượt giữ luồng suốt thời gian gọi mô hình, tới hàng chục giây. Chạy nó trên luồng
     * Tomcat nghĩa là 200 khách hỏi cùng lúc làm treo mọi endpoint khác của service — kể cả hàng
     * đợi của bàn hỗ trợ, thứ người trực đang nhìn đúng lúc đó. Trả {@code CompletableFuture} nhả
     * luồng ngay sau khi nhận; xem {@code AgentExecutorConfig}.
     *
     * <p>Hai cửa trước khi vào pool, và chúng chặn hai thứ khác nhau:
     *
     * <ol>
     *   <li>{@link TurnAdmission} — một người không được chiếm nhiều chỗ. Giới hạn tần suất ở
     *       gateway không thấy được điều này: 200 request <i>chậm</i> vẫn nằm trong 50 rps.
     *   <li>Hàng đợi hữu hạn của pool — cả hệ thống không nhận quá năng lực đã khai.
     * </ol>
     *
     * <p>Cả hai từ chối bằng 503 <b>ngay lập tức</b>. Một câu "thử lại sau" trong năm mili-giây tử
     * tế hơn hai phút chờ rồi hết hạn, và nó không giữ kết nối nào.
     */
    @PostMapping("/support")
    public CompletableFuture<AskResponse> ask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AskRequest request) {

        // userId lấy từ JWT đã xác thực. Đây là điểm duy nhất danh tính đi vào luồng, và nó KHÔNG
        // BAO GIỜ đến từ body: một trường "userId" trong request là cách mở cửa cho bất kỳ ai đọc
        // đơn hàng của bất kỳ ai.
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        UUID sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return submit(sessionId, userId, request.message());
        }

        // Kiểm khoá TRƯỚC khi giữ chỗ: một lần gửi lại được trả lời từ bản đã lưu thì không tiêu
        // suất đồng thời của ai cả.
        var replay = idempotency.beginOrReplay(userId, idempotencyKey, sessionId, request.message());
        if (replay.isPresent()) {
            return CompletableFuture.completedFuture(replay.get());
        }

        try {
            return submit(sessionId, userId, request.message()).whenComplete((response, error) -> {
                if (error == null) {
                    idempotency.complete(userId, idempotencyKey, response);
                } else {
                    // Nhả khoá khi hỏng, nếu không một lần 503 tạm thời khoá cứng đúng câu hỏi ấy:
                    // mọi lần thử lại sau đó đều rơi vào nhánh "đang xử lý" của một lượt đã chết.
                    idempotency.release(userId, idempotencyKey);
                }
            });
        } catch (RuntimeException e) {
            idempotency.release(userId, idempotencyKey);
            throw e;
        }
    }

    /** Giữ chỗ rồi đẩy sang pool của agent. Từ chối ở cả hai cửa đều là 503 tức thì. */
    private CompletableFuture<AskResponse> submit(UUID sessionId, UUID userId, String message) {
        if (!admission.tryAcquire(userId)) {
            throw new ApiException(
                    AiChatboxErrorCode.ASSISTANT_BUSY, "Bạn đang có một câu hỏi đang được xử lý, chờ chút nhé");
        }

        // SecurityContext nằm trong ThreadLocal nên KHÔNG tự đi sang luồng khác. Phải mang nó theo:
        // tool tra đơn hàng gọi ordering bằng chính access token của khách, và thiếu context thì
        // SecurityContextCallerCredentials ném ra "agent chỉ chạy trong request đã xác thực" —
        // một thông báo đúng nhưng trỏ sai chỗ, vì request vẫn đã xác thực đàng hoàng.
        SecurityContext security = SecurityContextHolder.getContext();
        try {
            return CompletableFuture.supplyAsync(() -> runTurn(security, sessionId, userId, message), agentExecutor);
        } catch (RejectedExecutionException e) {
            // Pool đầy VÀ hàng đợi đầy. Nhả chỗ vừa giữ, nếu không người này mất suất vĩnh viễn.
            admission.release(userId);
            throw new ApiException(AiChatboxErrorCode.ASSISTANT_BUSY, "Trợ lý đang bận, bạn thử lại sau ít phút nhé");
        }
    }

    private AskResponse runTurn(SecurityContext security, UUID sessionId, UUID userId, String message) {
        SecurityContextHolder.setContext(security);
        try {
            AgentReply reply = agent.executeAgentProcess(sessionId, userId, message);
            return new AskResponse(reply.sessionId(), reply.answer(), reply.toolsUsed());
        } finally {
            // Luồng của pool sống rất lâu và được dùng lại cho người khác. Không xoá context thì
            // lượt kế tiếp chạy trên đúng luồng ấy thừa hưởng danh tính của khách trước.
            SecurityContextHolder.clearContext();
            admission.release(userId);
        }
    }

    /**
     * Cả hội thoại, kèm phiếu chuyển tiếp nếu đang có.
     *
     * <p>Màn hình hỏi lại endpoint này vài giây một lần khi có phiếu mở — đó là cách câu trả lời
     * của người trực tới được khách. Không có phiếu thì không cần hỏi lại: trợ lý trả lời ngay
     * trong phản hồi của {@code POST /support}.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public HandoffViews.ChatThread thread(@PathVariable UUID sessionId) {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        return handoffs.customerThread(sessionId, userId, TRANSCRIPT_LIMIT);
    }

    /**
     * Nút "gặp nhân viên" — đường tường minh, không đi qua mô hình.
     *
     * <p>Tồn tại song song với việc nhận ý định trong câu chữ ({@code HandoffIntent}) vì hai thứ
     * phục vụ hai kiểu người dùng: người gõ "cho tôi gặp người thật", và người đi tìm một cái nút.
     * Bỏ nút đi thì nhóm thứ hai phải đoán ra đúng cách diễn đạt mà hệ thống nhận được.
     *
     * <p>Idempotent: bấm ba lần vẫn là một phiếu.
     */
    @PostMapping("/sessions/{sessionId}/handoff")
    public HandoffViews.HandoffRow requestHuman(@PathVariable UUID sessionId) {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        return handoffs.requestByCustomer(sessionId, userId);
    }
}
