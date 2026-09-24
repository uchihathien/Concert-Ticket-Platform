// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import com.nexaticket.platform.security.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public SupportChatController(CustomerSupportAgentUseCase agent, HandoffUseCase handoffs) {
        this.agent = agent;
        this.handoffs = handoffs;
    }

    /**
     * @param sessionId phiên hiện có; bỏ trống thì mở phiên mới. Client gửi lên nên đây là dữ liệu
     *     chưa tin được — quyền sở hữu được kiểm ở tầng dưới, không ở đây.
     * @param message giới hạn độ dài là chốt chặn chi phí, không phải chốt chặn kỹ thuật: token
     *     đầu vào tính tiền, và không có trần thì một lần dán nhầm cả cuốn sách là một hoá đơn.
     */
    public record AskRequest(UUID sessionId, @NotBlank @Size(max = 2000) String message) {}

    public record AskResponse(UUID sessionId, String answer, List<String> toolsUsed) {}

    @PostMapping("/support")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        // userId lấy từ JWT đã xác thực. Đây là điểm duy nhất danh tính đi vào luồng, và nó KHÔNG
        // BAO GIỜ đến từ body: một trường "userId" trong request là cách mở cửa cho bất kỳ ai đọc
        // đơn hàng của bất kỳ ai.
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        UUID sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID();

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, request.message());
        return new AskResponse(reply.sessionId(), reply.answer(), reply.toolsUsed());
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
