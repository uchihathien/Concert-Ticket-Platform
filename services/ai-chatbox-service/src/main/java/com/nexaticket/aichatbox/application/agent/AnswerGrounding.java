// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chốt chặn cuối: không cho trợ lý nói ra ngày diễn hay giá vé mà nó không tra được ở đâu cả.
 *
 * <h2>Vì sao một dòng cấm trong prompt là không đủ</h2>
 *
 * Prompt hệ thống đã ghi "chỉ nói những gì có trong kết quả tool". Mô hình 7B chạy tại chỗ vẫn vi
 * phạm, và nó vi phạm theo cách khó phát hiện nhất: khi {@code findEvents} không tìm ra sự kiện
 * nào, mô hình <b>lấp chỗ trống bằng kiến thức trong chính nó</b> — nó biết về concert ngoài đời
 * thật, nên nó mô tả địa điểm và giá vé của một sự kiện NexaTicket không hề bán. Khách đọc được một
 * câu trôi chảy, cụ thể, và sai hoàn toàn; không có dấu hiệu nào trong câu đó để họ nhận ra.
 *
 * <p>Trợ lý <b>không có</b> tool tìm kiếm internet. Cái gọi là "nó tra trên mạng" thực chất là bộ
 * nhớ của mô hình, và bộ nhớ ấy không thể tắt bằng cách nhờ.
 *
 * <h2>Điều kiện kích hoạt</h2>
 *
 * Chặn khi cả ba điều cùng đúng:
 *
 * <ol>
 *   <li>Không tool nào trả về dữ liệu — không sự kiện, không đơn hàng, không quy định. Bao gồm cả
 *       trường hợp mô hình <b>không gọi tool nào cả</b>.
 *   <li>Không có đoạn tri thức nào được lấy ra cho lượt này.
 *   <li>Câu trả lời vẫn chứa một con số kiểu ngày, giờ hoặc tiền.
 * </ol>
 *
 * Ba điều đó cùng đúng thì con số ấy <b>không thể</b> có nguồn nào ngoài bộ nhớ mô hình.
 *
 * <p><b>Điều kiện thứ nhất từng hẹp hơn, và hẹp sai.</b> Bản đầu chỉ chặn khi đã tra danh mục mà
 * không ra gì — nên nó bỏ lọt đúng trường hợp tệ nhất: mô hình <i>không gọi tool</i>, tự bịa ba sự
 * kiện "Rock Fest Miền Trung 2023" ở ba sân vận động không tồn tại, và đi qua chốt chặn vì không có
 * lần tra nào thất bại để mà phát hiện. Đo được thật, với {@code toolsUsed: []}.
 *
 * <p>Điều kiện thứ hai giữ cho nó không chặn oan: câu trả lời chính sách dựng từ kho tri thức được
 * phép nêu con số trong tài liệu, và lúc đó nguồn là đoạn tri thức chứ không phải bộ nhớ mô hình.
 */
final class AnswerGrounding {

    private AnswerGrounding() {}

    /** Ngày (4/10, 04/10/2026), giờ (19:00), tiền (950.000đ, 450000 VND). */
    private static final List<Pattern> FACT_SHAPES = List.of(
            Pattern.compile("\\b\\d{1,2}\\s*/\\s*\\d{1,2}(\\s*/\\s*\\d{2,4})?\\b"),
            Pattern.compile("\\b\\d{1,2}\\s*:\\s*\\d{2}\\b"),
            Pattern.compile("\\d[\\d.,]*\\s*(đ|đồng|vnd|vnđ)", Pattern.CASE_INSENSITIVE));

    private static final String NO_SUCH_EVENT =
            """
            Mình tìm trong danh mục của NexaTicket mà chưa thấy sự kiện này — có thể tên hơi khác, \
            hoặc NexaTicket chưa bán vé cho sự kiện đó. Bạn thử nhắn lại tên ngắn hơn, hoặc cho mình \
            biết bạn đang tìm sự kiện ở thành phố nào để mình tra lại nhé.""";

    /**
     * @param outcomes mọi kết quả tool của lượt này, theo đúng thứ tự đã chạy
     * @return câu trả lời gốc, hoặc câu thay thế khi phát hiện con số không có nguồn
     */
    static String enforce(String answer, List<ToolOutcome> outcomes, boolean knowledgeContextWasEmpty) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        // Có nguồn thì đi qua: kết quả tool có dữ liệu, HOẶC có đoạn tri thức nào được lấy ra.
        if (anyToolReturnedData(outcomes) || !knowledgeContextWasEmpty) {
            return answer;
        }
        return mentionsFactShape(answer) ? NO_SUCH_EVENT : answer;
    }

    /**
     * Có tool nào trả về dữ liệu thật không.
     *
     * <p>Đọc theo chuỗi {@code "found":true} trong payload thay vì mở JSON ra: payload do chính
     * {@code ToolDispatcher} ở ngay cạnh đây sinh ra, nên hình dạng của nó là chuyện nội bộ của hai
     * lớp này — và một lần parse JSON cho mỗi lượt chat chỉ để đọc một khoá boolean là phí.
     */
    private static boolean anyToolReturnedData(List<ToolOutcome> outcomes) {
        for (ToolOutcome outcome : outcomes) {
            if (!outcome.failed()
                    && outcome.payload() != null
                    && outcome.payload().contains("\"found\":true")) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsFactShape(String answer) {
        for (Pattern shape : FACT_SHAPES) {
            if (shape.matcher(answer).find()) {
                return true;
            }
        }
        return false;
    }
}
