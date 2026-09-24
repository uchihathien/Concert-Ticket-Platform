// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

/**
 * Nhà cung cấp mô hình đang dùng.
 *
 * <h2>Vì sao mặc định là LOCAL</h2>
 *
 * <p>Chat hỗ trợ là tính năng chạy suốt ngày với lưu lượng không đoán trước, và mỗi lượt là hai
 * lần tính tiền: một cho embedding, một cho mô hình — nhân lên với số vòng ReAct. Một cấu hình
 * mặc định có tính tiền nghĩa là mọi môi trường dev, mọi lần chạy CI, mọi lần ai đó thử nghịch
 * đều ghi vào hoá đơn thật.
 *
 * <p>Đảo mặc định lại cũng sửa một vấn đề vận hành thật: trước đây service <b>từ chối khởi động</b>
 * khi thiếu {@code ANTHROPIC_API_KEY}. Với môi trường local thì đó là một service không chạy được
 * nếu chưa có khoá của nhà cung cấp trả phí.
 *
 * <h2>Đánh đổi, nói thẳng</h2>
 *
 * <p>Mô hình local 7B gọi tool kém tin cậy hơn hẳn: nó bỏ sót tham số, gọi tool không tồn tại, và
 * thỉnh thoảng trả lời bằng lời trong khi lẽ ra phải tra cứu. Vòng ReAct đã chịu được những thứ đó
 * (tool lạ trả về lỗi, hết vòng thì chuyển người thật), nên phần lớn hậu quả là tỷ lệ chuyển sang
 * bàn hỗ trợ cao hơn.
 *
 * <p><b>Nhưng không chỉ có thế, và phần này đo được.</b> {@code qwen2.5:7b-instruct} chạy thật với
 * kho tri thức đã nạp, hỏi "thủ đô nước Pháp là thành phố nào" — một câu không có gì trong kho:
 *
 * <ul>
 *   <li>nó <b>tự trả lời "Paris"</b> từ kiến thức riêng, dù prompt hệ thống cấm nói những gì không
 *       có trong ngữ cảnh tham khảo hoặc kết quả tool;
 *   <li>nó đọc tên phần nội bộ ra cho khách ("Ngữ cảnh tham khảo không chứa…");
 *   <li>nó hứa "mình sẽ tìm kiếm thông tin cho bạn" — một việc nó không có tool để làm.
 * </ul>
 *
 * <p>Siết thêm ba dòng cấm vào prompt <b>không sửa được</b> ba hành vi trên; lần thử lại còn tệ hơn.
 * Nên rủi ro của bản local không phải "trả lời thiếu" mà là <b>trả lời dứt khoát về thứ nó không
 * được phép biết</b> — với một nền tảng bán vé thì đó là một câu sai về chính sách nói bằng giọng
 * chắc chắn. Coi {@code local} là cấu hình để PHÁT TRIỂN: nó đúng cho việc kiểm luồng, kiểm lưu trữ,
 * kiểm chuyển tiếp. Môi trường có khách thật thì đặt {@code AI_PROVIDER=anthropic}.
 *
 * <p>Tốc độ cũng nằm cùng kết luận đó: đo được <b>1,5 token/giây</b> khi sinh chữ trên CPU, nên một
 * câu trả lời 150 token mất khoảng 100 giây, và một lượt có tra cứu mất vài phút.
 *
 * <p>Nơi nào cần chất lượng cao hơn thì đặt {@code AI_PROVIDER=anthropic}, không phải sửa code.
 */
public enum LlmProvider {

    /** Ollama chạy trên máy/cụm của chính mình. Không tốn phí theo token. */
    LOCAL,

    /** API của Anthropic + Voyage. Chất lượng cao hơn, có hoá đơn. */
    ANTHROPIC;

    /** Khoá cấu hình chọn nhà cung cấp. Dùng ở {@code @ConditionalOnProperty} nên phải là hằng số. */
    public static final String PROPERTY = "nexaticket.aichatbox.provider";
}
