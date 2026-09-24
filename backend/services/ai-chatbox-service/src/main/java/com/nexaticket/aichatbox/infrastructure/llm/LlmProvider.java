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
 * (tool lạ trả về lỗi, hết vòng thì chuyển người thật), nên hậu quả là tỷ lệ chuyển sang bàn hỗ
 * trợ cao hơn — chứ không phải câu trả lời sai. Đó là hướng hỏng đúng.
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
