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
    public static final String FIND_EVENTS = "findEvents";
    public static final String GET_EVENT_DETAILS = "getEventDetails";

    /**
     * Tool <b>điều khiển</b>, không phải tool dữ liệu.
     *
     * <p>Nó không tra gì cả — nó chuyển cuộc hội thoại sang người thật. Vì vậy nó được xử lý ngay
     * trong vòng ReAct chứ không đi qua {@code ToolDispatcher}: dispatcher chỉ nhận một
     * {@code ToolInvocation} và không biết phiên nào, người nào. Truyền thêm hai tham số ấy vào
     * dispatcher chỉ để phục vụ một tool là làm hỏng hình dạng của nó cho cả hai tool còn lại.
     */
    public static final String ESCALATE_TO_HUMAN = "escalateToHuman";

    private SupportAgentTools() {}

    private static final List<ToolSpec> CATALOG = List.of(
            new ToolSpec(
                    FIND_EVENTS,
                    """
                    Tìm sự kiện đang bán vé theo TÊN mà khách nói, hoặc theo thành phố, hoặc theo nhóm. \
                    Gọi tool này bất cứ khi nào khách hỏi về một sự kiện, một đêm nhạc, một concert — \
                    kể cả khi họ chỉ nhớ một phần tên. Trả về tên đầy đủ, thành phố, địa điểm, suất diễn \
                    gần nhất, giá thấp nhất và mã slug. Dùng slug đó để gọi getEventDetails khi khách \
                    cần giá từng hạng vé hoặc toàn bộ suất diễn.""",
                    List.of(
                            ToolSpec.Param.optionalString(
                                    "query",
                                    "Tên hoặc một phần tên sự kiện đúng như khách gõ, ví dụ \"đêm nhạc Trịnh\". "
                                            + "Bỏ trống khi khách không nêu tên mà chỉ hỏi theo thành phố hoặc nhóm."),
                            ToolSpec.Param.optionalString(
                                    "city",
                                    "Thành phố, chỉ điền khi khách NÓI RA, ví dụ \"Hà Nội\", \"Đà Nẵng\". "
                                            + "Đừng tự đoán."),
                            ToolSpec.Param.optionalString(
                                    "category",
                                    "Nhóm sự kiện, chỉ điền khi khách nói rõ. Các giá trị dùng được: "
                                            + "nhac-song, san-khau, the-thao, hoi-thao."))),
            new ToolSpec(
                    GET_EVENT_DETAILS,
                    """
                    Tra chi tiết MỘT sự kiện theo slug: địa chỉ đầy đủ của địa điểm, từng suất diễn, \
                    giá của từng hạng vé, hạn bán vé. Chỉ gọi sau khi đã có slug từ findEvents — \
                    đừng tự dựng slug từ tên sự kiện.""",
                    List.of(ToolSpec.Param.requiredString(
                            "slug", "Mã slug lấy từ kết quả findEvents, ví dụ dem-nhac-trinh"))),
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
                    List.of(ToolSpec.Param.requiredString("eventId", "Mã sự kiện dạng UUID"))),
            new ToolSpec(
                    ESCALATE_TO_HUMAN,
                    """
                    Chuyển cuộc trò chuyện cho nhân viên hỗ trợ là người thật. Gọi khi: khách yêu cầu \
                    một ngoại lệ so với chính sách (hoàn tiền, đổi vé, đổi tên trên vé); hoặc khách \
                    đang khiếu nại và cần người xử lý; hoặc bạn đã tra cứu mà vẫn không đủ thông tin \
                    để trả lời. KHÔNG gọi chỉ vì câu hỏi khó — hãy thử tra cứu trước. Sau khi gọi \
                    tool này, hãy báo cho khách biết là đang chuyển cho nhân viên.""",
                    List.of(ToolSpec.Param.requiredString(
                            "reason",
                            "Một câu tiếng Việt nói rõ khách đang cần gì, để nhân viên đọc là hiểu ngay "
                                    + "mà không phải mở lại cả hội thoại"))));

    public static List<ToolSpec> all() {
        return CATALOG;
    }
}
