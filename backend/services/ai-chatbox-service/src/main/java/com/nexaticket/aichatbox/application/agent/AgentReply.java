// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import java.util.List;
import java.util.UUID;

/**
 * Câu trả lời của agent.
 *
 * @param toolsUsed tên các tool đã chạy để ra câu trả lời này. Trả về cho client không phải để
 *     hiển thị mà để gỡ rối: khi khách báo "agent trả lời sai", câu hỏi đầu tiên luôn là nó lấy
 *     thông tin từ đâu — kho tri thức hay đơn hàng thật.
 */
public record AgentReply(UUID sessionId, String answer, List<String> toolsUsed) {

    public AgentReply {
        toolsUsed = List.copyOf(toolsUsed);
    }
}
