// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.ActionCard;
import java.util.List;
import java.util.UUID;

/**
 * Câu trả lời của agent.
 *
 * @param toolsUsed tên các tool đã chạy để ra câu trả lời này. Trả về cho client không phải để
 *     hiển thị mà để gỡ rối: khi khách báo "agent trả lời sai", câu hỏi đầu tiên luôn là nó lấy
 *     thông tin từ đâu — kho tri thức hay đơn hàng thật.
 * @param action thẻ cho giao diện vẽ nút (đề xuất đặt vé, thanh toán) — {@code null} khi lượt không
 *     có thẻ; đi riêng khỏi {@code answer} để giao diện không parse chữ của mô hình
 */
public record AgentReply(UUID sessionId, String answer, List<String> toolsUsed, ActionCard action) {

    public AgentReply {
        toolsUsed = List.copyOf(toolsUsed);
    }

    /** Lượt không có thẻ: chuyển người thật, hết vòng, trợ lý không dùng được. */
    public AgentReply(UUID sessionId, String answer, List<String> toolsUsed) {
        this(sessionId, answer, toolsUsed, null);
    }
}
