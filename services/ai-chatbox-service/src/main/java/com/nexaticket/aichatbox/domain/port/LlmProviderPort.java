// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolSpec;
import java.util.List;

/**
 * Một lượt gọi mô hình. <b>Không</b> có vòng lặp ở đây.
 *
 * <p>Cố ý: vòng ReAct thuộc về tầng application, nơi nhìn thấy chính sách nghiệp vụ — được gọi tool
 * nào, lặp tối đa mấy vòng, hỏng thì nói gì với khách. SDK nào cũng có sẵn một "tool runner" chạy
 * vòng lặp hộ; dùng nó thì chính sách đó chui vào thư viện của nhà cung cấp và không test được nếu
 * không gọi API thật.
 */
public interface LlmProviderPort {

    /**
     * @return {@link Answer} khi mô hình đã trả lời xong, {@link ToolRequest} khi nó cần dữ liệu
     * @throws LlmUnavailableException khi không gọi được mô hình, hoặc nó từ chối trả lời
     */
    LlmTurn complete(LlmRequest request);

    /**
     * @param transcript toàn bộ hội thoại, cũ trước mới sau. Prompt hệ thống KHÔNG nằm trong này.
     * @param tools danh sách tool mô hình được phép gọi lượt này
     */
    record LlmRequest(String systemPrompt, List<Exchange> transcript, List<ToolSpec> tools) {
        public LlmRequest {
            transcript = List.copyOf(transcript);
            tools = List.copyOf(tools);
        }
    }

    /** Kết quả một lượt: hoặc lời, hoặc yêu cầu chạy tool. Không có khả năng thứ ba. */
    sealed interface LlmTurn {

        record Answer(String text) implements LlmTurn {}

        /**
         * @param providerEcho nội dung nguyên bản của lượt này, để vòng sau gửi lại nguyên vẹn.
         *     Xem {@link com.nexaticket.aichatbox.domain.model.Exchange.AssistantRequestedTools}.
         */
        record ToolRequest(List<ToolInvocation> calls, Object providerEcho) implements LlmTurn {
            public ToolRequest {
                calls = List.copyOf(calls);
            }
        }
    }
}
