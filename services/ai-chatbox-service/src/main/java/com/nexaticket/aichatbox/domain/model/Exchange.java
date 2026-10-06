// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.List;

/**
 * Một mục trong bản ghi hội thoại gửi cho mô hình.
 *
 * <p><b>Vì sao không chỉ là {@code (role, text)}.</b> Vòng ReAct cần gửi lại cho mô hình đúng
 * những gì đã xảy ra, kể cả phần không phải văn bản: nó đã <i>yêu cầu gọi tool nào</i> và
 * <i>tool trả về gì</i>. Ép hai thứ đó thành chuỗi trong một message "assistant" là cách làm hỏng
 * lần gọi thứ hai — mô hình mất liên kết giữa lời gọi và kết quả, rồi gọi lại chính tool vừa chạy.
 *
 * <p>Kiểu sealed này là hợp đồng giữa tầng application (chủ vòng lặp) và adapter LLM (biết cách
 * dịch sang định dạng của nhà cung cấp). Đổi nhà cung cấp thì sửa adapter, không sửa vòng lặp.
 */
public sealed interface Exchange {

    /** Khách hỏi. */
    record UserSaid(String text) implements Exchange {}

    /** Mô hình trả lời bằng lời. Kết thúc một lượt. */
    record AssistantSaid(String text) implements Exchange {}

    /**
     * Mô hình đòi chạy tool. Chưa phải câu trả lời.
     *
     * @param providerEcho khối nội dung <b>nguyên bản</b> của lượt đó, chỉ adapter hiểu. Nghe như
     *     một lỗ hổng trừu tượng, nhưng nó có lý do cụ thể: khi mô hình vừa suy luận vừa gọi tool,
     *     lượt trả lời chứa cả khối suy luận, và giao thức đòi gửi lại khối đó <b>nguyên vẹn</b> ở
     *     vòng sau. Dựng lại nó từ {@code calls} là không thể — nội dung đã mất. Bỏ qua nó thì API
     *     từ chối request, và lỗi khi đó nói về định dạng chứ không nói rằng ta đã đánh rơi một
     *     khối. Kiểu {@code Object} giữ cho domain không phải biết tên một class nào của SDK.
     */
    record AssistantRequestedTools(List<ToolInvocation> calls, Object providerEcho) implements Exchange {
        public AssistantRequestedTools {
            calls = List.copyOf(calls);
        }
    }

    /**
     * Kết quả tool trả về mô hình.
     *
     * <p>Phải chứa <b>đủ</b> kết quả cho <b>mọi</b> lời gọi trong {@link AssistantRequestedTools}
     * ngay trước nó, trong cùng một mục. Thiếu một cái là request kế tiếp sai định dạng và API từ
     * chối; tách ra nhiều mục thì mô hình học được rằng gọi song song không được đáp ứng và thôi
     * gọi song song.
     */
    record ToolsReturned(List<ToolOutcome> outcomes) implements Exchange {
        public ToolsReturned {
            outcomes = List.copyOf(outcomes);
        }
    }
}
