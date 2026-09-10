// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.ToolSpec;
import java.util.List;

/**
 * Danh mục tool của agent hỗ trợ.
 *
 * <p><b>Mô tả tool là prompt, không phải tài liệu.</b> Đây là thứ duy nhất mô hình có để quyết định
 * gọi hay không gọi. "Tra đơn hàng" thì nó gọi cả khi khách hỏi "đơn hàng bên bạn giao mấy ngày" —
 * một câu hỏi chung không có mã đơn nào. Mô tả nói rõ <i>khi nào</i> dùng và <i>cần gì</i> mới cắt
 * được những lần gọi vô ích đó, và mỗi lần gọi vô ích là một vòng lặp có tính tiền.
 */
public final class SupportAgentTools {

    public static final String GET_ORDER_STATUS = "getOrderStatus";
    public static final String GET_EVENT_RULES = "getEventRules";

    private SupportAgentTools() {}

    private static final List<ToolSpec> CATALOG = List.of(
            new ToolSpec(
                    GET_ORDER_STATUS,
                    """
                    Tra trạng thái thanh toán, tổng tiền, hạn thanh toán và danh sách vé của MỘT đơn hàng \
                    thuộc về chính người đang chat. Chỉ gọi khi khách đã cung cấp mã đơn hàng dạng UUID. \
                    Nếu khách hỏi về đơn hàng nhưng chưa đưa mã, hãy hỏi xin mã đơn thay vì gọi tool này.""",
                    List.of(ToolSpec.Param.requiredString(
                            "orderId", "Mã đơn hàng dạng UUID, ví dụ 3fa85f64-5717-4562-b3fc-2c963f66afa6"))),
            new ToolSpec(
                    GET_EVENT_RULES,
                    """
                    Tra quy định của một sự kiện: giới hạn độ tuổi, vật phẩm được và không được mang vào, \
                    giờ mở cửa. Chỉ gọi khi phần NGỮ CẢNH THAM KHẢO không trả lời được câu hỏi và khách \
                    đang nói về một sự kiện cụ thể có mã UUID.""",
                    List.of(ToolSpec.Param.requiredString("eventId", "Mã sự kiện dạng UUID"))));

    public static List<ToolSpec> all() {
        return CATALOG;
    }
}
