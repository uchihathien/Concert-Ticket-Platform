// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Nhận ra câu "cho tôi gặp người thật" mà <b>không</b> hỏi mô hình.
 *
 * <h2>Vì sao không để mô hình tự quyết việc này</h2>
 *
 * <p>Mô hình đã có tool {@code escalateToHuman}, và nó là đường chính cho trường hợp "trợ lý không
 * trả lời được". Nhưng khi khách nói thẳng là muốn gặp người, ba điều dưới đây quan trọng hơn sự
 * tinh tế:
 *
 * <ul>
 *   <li><b>Không tốn tiền.</b> Phát hiện ở đây cắt trọn một lượt gọi API cho câu hỏi dễ nhận ra
 *       nhất trong toàn bộ luồng.
 *   <li><b>Không có độ trễ.</b> Khách đòi gặp người thường đang bực. Bắt họ chờ thêm một lượt suy
 *       luận để nhận về đúng thứ họ vừa yêu cầu là cách làm họ bực hơn.
 *   <li><b>Không thể từ chối.</b> Một prompt có thể bị mô hình diễn giải thành "hãy thử giúp khách
 *       thêm lần nữa". Một câu {@code if} thì không.
 * </ul>
 *
 * <h2>Đánh đổi</h2>
 *
 * <p>Khớp theo từ khoá thì bỏ sót cách diễn đạt lạ, và bắt nhầm câu như "nhân viên soát vé có kiểm
 * tra căn cước không". Cả hai đều chấp nhận được, và không cân xứng: bỏ sót thì mô hình vẫn còn
 * tool chuyển tiếp làm lưới đỡ; bắt nhầm thì khách gặp người thật sớm hơn cần thiết — phiền cho
 * bàn hỗ trợ, không phiền cho khách. Nên danh sách dưới đây ưu tiên cụm từ <b>có chủ ngữ rõ ràng</b>
 * thay vì từ đơn như "nhân viên".
 */
public final class HandoffIntent {

    private HandoffIntent() {}

    /**
     * So khớp trên chuỗi đã bỏ dấu, nên mỗi mục ở đây viết không dấu.
     *
     * <p>Khách gõ tiếng Việt không dấu là chuyện rất thường trên điện thoại — "cho toi gap nhan
     * vien" phải khớp đúng như "cho tôi gặp nhân viên".
     */
    private static final List<String> PHRASES = List.of(
            "gap nguoi that",
            "gap nhan vien",
            "gap tu van vien",
            "noi chuyen voi nguoi",
            "noi chuyen voi nhan vien",
            "chuyen cho nhan vien",
            "chuyen nhan vien",
            "can nguoi that",
            "muon gap nguoi",
            "cho toi gap",
            "khong muon chat voi bot",
            "khong muon noi chuyen voi bot",
            "bot khong hieu",
            "may khong hieu",
            "talk to a human",
            "speak to a human",
            "human agent",
            "real person");

    /** Khách có đang đòi gặp người thật không. */
    public static boolean isExplicitRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String folded = fold(message);
        return PHRASES.stream().anyMatch(folded::contains);
    }

    /**
     * Bỏ dấu tiếng Việt và hạ chữ thường.
     *
     * <p>{@code đ}/{@code Đ} phải xử lý riêng: nó không phải chữ {@code d} có dấu phụ, nên
     * {@link Normalizer} tách ra không được và "khong muon" sẽ không khớp "không muốn" nếu thiếu
     * bước này.
     */
    private static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
