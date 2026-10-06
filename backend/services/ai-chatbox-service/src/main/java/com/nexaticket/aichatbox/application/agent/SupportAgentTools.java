// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.ToolSpec;
import java.util.List;
import java.util.Set;

/**
 * Danh mục tool của agent hỗ trợ.
 *
 * <p><b>Mô tả tool là prompt, không phải tài liệu.</b> Đây là thứ duy nhất mô hình có để quyết định
 * gọi hay không gọi. "Tra đơn hàng" thì nó gọi cả khi khách hỏi "đơn hàng bên bạn giao mấy ngày" —
 * một câu hỏi chung không có mã đơn nào. Mô tả nói rõ <i>khi nào</i> dùng và <i>cần gì</i> mới cắt
 * được những lần gọi vô ích đó, và mỗi lần gọi vô ích là một vòng lặp có tính tiền.
 *
 * <h2>Hai loại tool</h2>
 *
 * <p><b>Tool dữ liệu</b> tra cứu và trả về JSON; vòng ReAct chạy chúng qua {@code ToolDispatcher}
 * rồi đưa kết quả lại cho mô hình. <b>Tool điều khiển</b> ({@link #CONTROL_TOOLS}) thay đổi trạng
 * thái của chính cuộc hội thoại — mở một phiếu cho người thật — và vì vậy cần biết phiên nào, người
 * nào; chúng được xử lý ngay trong vòng ReAct, và thường kết thúc lượt.
 */
public final class SupportAgentTools {

    public static final String GET_ORDER_STATUS = "getOrderStatus";
    public static final String GET_EVENT_RULES = "getEventRules";
    public static final String FIND_EVENTS = "findEvents";
    public static final String GET_EVENT_DETAILS = "getEventDetails";
    public static final String GET_CUSTOMER_PROFILE_AND_HISTORY = "getCustomerProfileAndHistory";
    public static final String INITIATE_TICKET_BOOKING = "initiateTicketBooking";

    /**
     * Tool <b>điều khiển</b>, không phải tool dữ liệu.
     *
     * <p>Nó không tra gì cả — nó chuyển cuộc hội thoại sang người thật. Vì vậy nó được xử lý ngay
     * trong vòng ReAct chứ không đi qua {@code ToolDispatcher}: dispatcher chỉ nhận một
     * {@code ToolInvocation} và không biết phiên nào, người nào. Truyền thêm hai tham số ấy vào
     * dispatcher chỉ để phục vụ vài tool là làm hỏng hình dạng của nó cho mọi tool còn lại.
     */
    public static final String ESCALATE_TO_HUMAN = "escalateToHuman";

    /** Tool điều khiển: xét chính sách rồi mở phiếu hoàn vé, hoặc từ chối ngay. */
    public static final String REQUEST_TICKET_REFUND = "requestTicketRefund";

    /** Tool điều khiển: mở phiếu sự cố theo khung mẫu. */
    public static final String REPORT_INCIDENT = "reportIncident";

    /** Những tool vòng ReAct phải tự xử lý vì chúng cần phiên và người dùng. */
    public static final Set<String> CONTROL_TOOLS = Set.of(ESCALATE_TO_HUMAN, REQUEST_TICKET_REFUND, REPORT_INCIDENT);

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
                    Tra chi tiết MỘT sự kiện theo slug: địa chỉ đầy đủ của địa điểm, từng suất diễn kèm \
                    sessionId, giá của từng hạng vé kèm zoneCode, hạn bán vé. Chỉ gọi sau khi đã có slug \
                    từ findEvents — đừng tự dựng slug từ tên sự kiện. sessionId và zoneCode ở đây là \
                    thứ initiateTicketBooking cần.""",
                    List.of(ToolSpec.Param.requiredString(
                            "slug", "Mã slug lấy từ kết quả findEvents, ví dụ dem-nhac-trinh"))),
            new ToolSpec(
                    GET_ORDER_STATUS,
                    """
                    Tra trạng thái thanh toán, tổng tiền, hạn thanh toán và danh sách vé của MỘT đơn hàng \
                    thuộc về chính người đang chat. Chỉ gọi khi khách đã cung cấp mã đơn hàng dạng UUID. \
                    Nếu khách hỏi về đơn hàng nhưng chưa đưa mã, hãy gọi getCustomerProfileAndHistory \
                    để xem các đơn gần nhất thay vì hỏi xin mã.""",
                    List.of(ToolSpec.Param.requiredString(
                            "orderId", "Mã đơn hàng dạng UUID, ví dụ 3fa85f64-5717-4562-b3fc-2c963f66afa6"))),
            new ToolSpec(
                    GET_CUSTOMER_PROFILE_AND_HISTORY,
                    """
                    Lấy tên của chính người đang chat và 5 đơn hàng gần nhất của họ: mã đơn, số đơn, \
                    trạng thái, tổng tiền, hạn thanh toán. Gọi khi khách hỏi "đơn của tôi", "vé của \
                    mình", "tôi đã mua gì" mà KHÔNG nêu mã đơn — kết quả có orderId để gọi tiếp \
                    getOrderStatus, requestTicketRefund hoặc reportIncident. Không cần tham số: danh \
                    tính lấy từ phiên đăng nhập, không bao giờ từ câu chữ của khách.""",
                    List.of()),
            new ToolSpec(
                    GET_EVENT_RULES,
                    """
                    Tra quy định của một sự kiện: giới hạn độ tuổi, vật phẩm được và không được mang vào, \
                    giờ mở cửa, và chính sách hoàn vé. Chỉ gọi khi phần NGỮ CẢNH THAM KHẢO không trả lời \
                    được câu hỏi và khách đang nói về một sự kiện cụ thể có mã UUID.""",
                    List.of(ToolSpec.Param.requiredString("eventId", "Mã sự kiện dạng UUID"))),
            new ToolSpec(
                    INITIATE_TICKET_BOOKING,
                    """
                    Giữ chỗ và tạo đơn hàng cho khách: hệ thống giữ chỗ trong 10 phút rồi lập đơn với \
                    hạn thanh toán 15 phút, trả về số đơn, tổng tiền và đường dẫn thanh toán. CHỈ gọi \
                    sau khi khách đã XÁC NHẬN rõ ràng ba thứ: sự kiện nào, suất nào, hạng vé nào và mấy \
                    vé — nhắc lại cho khách và chờ họ đồng ý trước. Đừng gọi khi khách mới hỏi giá hay \
                    "còn vé không". sessionId và zoneCode lấy từ getEventDetails, đừng tự dựng. Hệ thống \
                    tự chọn chỗ tốt nhất trong khu; khách muốn tự chọn ghế thì mời họ vào trang sự kiện.""",
                    List.of(
                            ToolSpec.Param.requiredString("slug", "Mã slug của sự kiện, từ findEvents"),
                            ToolSpec.Param.requiredString(
                                    "sessionId", "Mã suất diễn dạng UUID, lấy từ trường sessionId của getEventDetails"),
                            ToolSpec.Param.requiredString(
                                    "zoneCode", "Mã khu của hạng vé khách chọn, lấy từ trường zoneCode của getEventDetails"),
                            new ToolSpec.Param("quantity", "integer", "Số vé, từ 1 đến 6", true))),
            new ToolSpec(
                    REQUEST_TICKET_REFUND,
                    """
                    Xin hoàn vé cho MỘT đơn đã thanh toán của chính người đang chat. Tool tự đối chiếu \
                    chính sách hoàn vé của sự kiện: nếu không được hoàn thì trả về lý do để bạn nói lại \
                    với khách; nếu được thì mở phiếu cho nhân viên xem xét và kết thúc lượt. Gọi khi khách \
                    nói muốn hoàn tiền, trả vé, đổi vé, đổi tên trên vé. Cần mã đơn dạng UUID — chưa có \
                    thì gọi getCustomerProfileAndHistory để khách chọn. KHÔNG hứa hoàn tiền trước khi \
                    gọi tool này.""",
                    List.of(
                            ToolSpec.Param.requiredString("orderId", "Mã đơn hàng dạng UUID"),
                            ToolSpec.Param.optionalString(
                                    "reason", "Lý do khách nêu, chép lại bằng một câu tiếng Việt ngắn"))),
            new ToolSpec(
                    REPORT_INCIDENT,
                    """
                    Mở phiếu sự cố cho nhân viên xử lý, theo đúng loại. Gọi khi khách báo: đã thanh toán \
                    mà chưa có vé (TICKET_NOT_RECEIVED); đã chuyển khoản mà đơn vẫn chờ thanh toán \
                    (PAYMENT_NOT_CONFIRMED); mã QR không quét được ở cổng (QR_NOT_SCANNABLE); vé ghi \
                    sai chỗ, sai suất (WRONG_TICKET_DETAILS); sự kiện đổi lịch hoặc huỷ (EVENT_CHANGED); \
                    sự cố khác (OTHER). Bốn loại đầu CẦN mã đơn — chưa có thì gọi \
                    getCustomerProfileAndHistory trước. Tool kết thúc lượt sau khi mở phiếu.""",
                    List.of(
                            ToolSpec.Param.requiredString(
                                    "kind",
                                    "Một trong: TICKET_NOT_RECEIVED, PAYMENT_NOT_CONFIRMED, QR_NOT_SCANNABLE, "
                                            + "WRONG_TICKET_DETAILS, EVENT_CHANGED, OTHER"),
                            ToolSpec.Param.requiredString(
                                    "description",
                                    "Mô tả sự cố bằng lời của khách, một tới hai câu: chuyện gì xảy ra, lúc nào"),
                            ToolSpec.Param.optionalString(
                                    "orderId", "Mã đơn hàng dạng UUID liên quan, nếu có"))),
            new ToolSpec(
                    ESCALATE_TO_HUMAN,
                    """
                    Chuyển cuộc trò chuyện cho nhân viên hỗ trợ là người thật. Gọi khi: khách khiếu nại và \
                    cần người xử lý; khách cần một việc không có tool nào làm được; hoặc bạn đã tra cứu mà \
                    vẫn không đủ thông tin để trả lời. KHÔNG gọi chỉ vì câu hỏi khó — hãy thử tra cứu \
                    trước. Hoàn vé thì dùng requestTicketRefund, sự cố thì dùng reportIncident — chúng \
                    gom sẵn dữ liệu cho nhân viên. Sau khi gọi tool này, hãy báo cho khách biết là đang \
                    chuyển cho nhân viên.""",
                    List.of(ToolSpec.Param.requiredString(
                            "reason",
                            "Một câu tiếng Việt nói rõ khách đang cần gì, để nhân viên đọc là hiểu ngay "
                                    + "mà không phải mở lại cả hội thoại"))));

    public static List<ToolSpec> all() {
        return CATALOG;
    }
}
