// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.platform.security.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent hỗ trợ khách hàng. */
@RestController
@RequestMapping("/v1/chat/agent")
public class SupportChatController {

    private final CustomerSupportAgentUseCase agent;

    public SupportChatController(CustomerSupportAgentUseCase agent) {
        this.agent = agent;
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
}
