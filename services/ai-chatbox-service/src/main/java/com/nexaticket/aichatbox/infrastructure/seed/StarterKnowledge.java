// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.seed;

import java.util.List;

/**
 * Tri thức nền của nền tảng — thứ trợ lý biết trước khi đội vận hành soạn thêm gì.
 *
 * <h2>Mọi câu ở đây phải ĐÚNG với hành vi thật của hệ thống</h2>
 *
 * <p>Đây không phải văn bản tiếp thị. Trợ lý bị prompt hệ thống buộc "chỉ nói những gì có trong
 * ngữ cảnh tham khảo", nên mỗi câu dưới đây là một câu nó sẽ nói với khách như một sự thật. Một
 * con số sai ở đây tệ hơn hẳn một kho tri thức rỗng: kho rỗng thì trợ lý nói "mình chưa tra được",
 * còn số sai thì nó trả lời dứt khoát và sai.
 *
 * <p>Vì vậy từng con số ở đây đều truy được về mã nguồn:
 *
 * <ul>
 *   <li>giữ chỗ 10 phút — {@code hold_ttl_seconds DEFAULT 600} trong V0100 của inventory, và
 *       {@code CHECK (hold_ttl_seconds BETWEEN 60 AND 3600)} là dải ban tổ chức đặt được;
 *   <li>hạn thanh toán 15 phút — {@code nexaticket.ordering.payment-window: 15m};
 *   <li>hotline 1900 1234 (8:00–22:00) — khớp với {@code SupportAgentPrompts.SYSTEM}, nếu không
 *       thì trợ lý đọc hai số khác nhau tuỳ câu hỏi rơi vào nhánh nào.
 * </ul>
 *
 * <h2>Chỗ nào hệ thống chưa làm thì nói là chưa làm</h2>
 *
 * <p>Hoàn vé và đổi vé <b>không</b> có luồng tự động. Viết một chính sách hoàn vé nghe hợp lý vào
 * đây là dạy trợ lý hứa một việc không ai thực hiện được — đúng thứ mà cả thiết kế handoff tồn tại
 * để tránh. Nên những mục ấy nói thẳng là phải qua nhân viên hỗ trợ.
 */
final class StarterKnowledge {

    private StarterKnowledge() {}

    record Chunk(String title, String content) {}

    static List<Chunk> all() {
        return List.of(
                new Chunk(
                        "Cách đặt vé trên NexaTicket",
                        """
                        Bạn chọn sự kiện và suất diễn, rồi chọn chỗ trên sơ đồ (vé ngồi) hoặc chọn số lượng \
                        (vé đứng). Chỗ bạn chọn được giữ lại trong 10 phút để bạn hoàn tất đơn hàng. Sau khi \
                        đặt đơn, bạn chuyển khoản theo mã VietQR hiện trên màn hình. Khi ngân hàng báo có, vé \
                        điện tử vào ví vé trong tài khoản của bạn ngay lập tức. Toàn bộ quá trình cần đăng nhập."""),
                new Chunk(
                        "Thời gian giữ chỗ là bao lâu",
                        """
                        Mặc định là 10 phút kể từ lúc bạn chọn chỗ, và màn hình có đồng hồ đếm ngược. Hết thời \
                        gian mà chưa đặt đơn thì chỗ được trả lại cho người khác chọn — đây là cách để một người \
                        không giữ chỗ vô thời hạn trong lúc nhiều người đang chờ. Ban tổ chức có thể đặt thời \
                        gian khác cho từng suất diễn, trong khoảng từ 1 phút đến 60 phút."""),
                new Chunk(
                        "Hạn thanh toán sau khi đặt đơn",
                        """
                        Bạn có 15 phút để chuyển khoản kể từ khi đơn hàng được tạo. Quá hạn thì đơn tự huỷ và \
                        chỗ được trả lại cho người khác. Nếu bạn đã chuyển khoản ngay trước lúc hết hạn, hệ \
                        thống vẫn ghi nhận khi ngân hàng báo có — nhưng nếu đơn đã huỷ và chỗ đã có người khác \
                        giữ thì cần nhân viên hỗ trợ xử lý."""),
                new Chunk(
                        "Các hình thức thanh toán được chấp nhận",
                        """
                        NexaTicket nhận chuyển khoản ngân hàng qua mã VietQR. Bạn quét mã bằng ứng dụng ngân \
                        hàng, nội dung chuyển khoản đã được điền sẵn — giữ nguyên nội dung đó để hệ thống khớp \
                        đúng đơn của bạn. Nền tảng không thu tiền mặt và không lưu thông tin thẻ của bạn."""),
                new Chunk(
                        "Chuyển khoản sai số tiền",
                        """
                        Nếu số tiền chuyển vào ít hơn giá trị đơn hàng, hệ thống không phát vé và giữ đơn ở \
                        trạng thái chờ. Chuyển thừa cũng không tự động phát vé. Cả hai trường hợp đều cần nhân \
                        viên hỗ trợ đối chiếu và xử lý, nên bạn hãy giữ lại biên lai chuyển khoản và cung cấp \
                        mã đơn hàng khi liên hệ."""),
                new Chunk(
                        "Đã chuyển tiền nhưng chưa thấy vé",
                        """
                        Ngân hàng thường báo có trong vòng vài phút, và vé xuất hiện trong ví vé ngay khi đó. \
                        Nếu sau ít phút vẫn chưa thấy, bạn kiểm tra lại nội dung chuyển khoản có bị sửa không — \
                        nội dung sai làm hệ thống không khớp được đơn. Cung cấp mã đơn hàng để được tra cứu \
                        chính xác tình trạng."""),
                new Chunk(
                        "Nhận vé ở đâu sau khi thanh toán",
                        """
                        Vé điện tử nằm trong mục ví vé của tài khoản bạn trên NexaTicket. Mỗi vé có một mã QR \
                        riêng dùng để vào cửa. Không có vé giấy và không cần in ra; bạn chỉ cần mở ví vé trên \
                        điện thoại tại cửa soát vé."""),
                new Chunk(
                        "Mất điện thoại hoặc đổi máy thì vé còn không",
                        """
                        Vé gắn với tài khoản chứ không gắn với thiết bị, nên bạn đăng nhập trên máy khác là thấy \
                        lại đầy đủ. Mã QR trên vé không chứa thông tin cá nhân của bạn, nên ảnh chụp màn hình bị \
                        lộ cũng không làm lộ danh tính — nhưng người cầm được ảnh đó có thể dùng vé, nên bạn \
                        đừng chia sẻ công khai."""),
                new Chunk(
                        "Soát vé tại cửa diễn ra thế nào",
                        """
                        Nhân viên quét mã QR trên vé điện tử của bạn. Mỗi vé chỉ vào cửa được một lần; lần quét \
                        thứ hai sẽ bị từ chối. Bạn nên tới sớm trước giờ diễn để tránh xếp hàng, và giữ pin điện \
                        thoại đủ để mở được ví vé."""),
                new Chunk(
                        "Khác nhau giữa vé ngồi và vé đứng",
                        """
                        Vé ngồi gắn với một chỗ cụ thể, có mã ghế dạng khu - hàng - số ghế, và bạn chọn đúng chỗ \
                        đó trên sơ đồ. Vé đứng chỉ gắn với một khu vực, không có số ghế, và bạn chọn chỗ đứng tự \
                        do trong khu khi vào. Một đơn hàng có thể gồm cả hai loại."""),
                new Chunk(
                        "Giới hạn số vé mỗi người được mua",
                        """
                        Ban tổ chức đặt trần số vé cho từng sự kiện, nên con số khác nhau tuỳ sự kiện. Trần này \
                        tính trên tài khoản của bạn, kể cả khi bạn đặt làm nhiều lần hoặc mở nhiều tab. Khi chạm \
                        trần, hệ thống sẽ báo ngay lúc bạn chọn chỗ chứ không để tới bước thanh toán."""),
                new Chunk(
                        "Hoàn vé, đổi vé và đổi tên trên vé",
                        """
                        NexaTicket không xử lý hoàn vé hay đổi vé tự động. Chính sách khác nhau theo từng sự kiện \
                        và do ban tổ chức quyết định, nên những yêu cầu này cần nhân viên hỗ trợ xem xét từng \
                        trường hợp. Bạn nhắn ngay trong khung chat này và nêu mã đơn hàng cùng lý do; nhân viên \
                        sẽ tiếp nhận và trả lời."""),
                new Chunk(
                        "Sự kiện bị hoãn hoặc huỷ",
                        """
                        Khi một suất diễn bị hoãn hoặc huỷ, ban tổ chức thông báo tới người đã mua vé. Cách xử lý \
                        vé đã bán do ban tổ chức quyết định và khác nhau theo từng sự kiện. Bạn liên hệ nhân viên \
                        hỗ trợ kèm mã đơn hàng để được hướng dẫn đúng theo sự kiện của mình."""),
                new Chunk(
                        "Liên hệ bộ phận hỗ trợ",
                        """
                        Bạn nhắn ngay trong khung chat này; nếu cần người thật, gõ "cho tôi gặp nhân viên" hoặc \
                        bấm nút Gặp nhân viên và cuộc trò chuyện sẽ được chuyển cho nhân viên hỗ trợ cùng toàn bộ \
                        nội dung phía trên. Ngoài ra có hotline 1900 1234, hoạt động 8:00–22:00 hằng ngày."""));
    }
}
