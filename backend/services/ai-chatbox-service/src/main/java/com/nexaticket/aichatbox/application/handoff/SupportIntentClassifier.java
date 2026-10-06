// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.domain.model.SupportIntent;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gắn nhãn ý định cho một phiếu từ câu khách đang hỏi — <b>không</b> hỏi mô hình.
 *
 * <h2>Vì sao so chuỗi là đủ</h2>
 *
 * <p>Nhãn này để <i>chia việc</i> ở bàn hỗ trợ, không để trả lời khách. Gắn sai thì một người trực
 * mở phiếu ra, thấy không phải việc của mình, và chuyển cho đồng nghiệp — mất một phút. Gọi mô hình
 * để gắn nhãn chính xác hơn vài phần trăm thì mỗi phiếu là một lần gọi API có tính tiền, nằm đúng
 * trên đường mà khách đang bực và đang chờ. Hai cái giá không cân nhau.
 *
 * <p>Và phần lớn phiếu không cần đoán: tool {@code requestTicketRefund} mở phiếu REFUND, tool
 * {@code reportIncident} mở phiếu INCIDENT — nhãn đi kèm hành động. Lớp này chỉ xử lý phần còn
 * lại: khách bấm nút hoặc gõ "cho tôi gặp người", và trợ lý bỏ cuộc giữa chừng.
 *
 * <h2>Thứ tự ưu tiên</h2>
 *
 * <p>Nhóm được xét theo thứ tự khai bên dưới, nhóm nào khớp trước thì thắng. Thứ tự ấy có chủ
 * đích: "hoàn tiền vé bị lỗi QR" là một yêu cầu hoàn tiền (REFUND) chứ không phải sự cố QR
 * (INCIDENT), vì cái khách <i>muốn</i> là tiền. Cụm từ hoàn/đổi vì thế đứng trước.
 */
public final class SupportIntentClassifier {

    private SupportIntentClassifier() {}

    /** Mọi mục viết không dấu, chữ thường — so khớp trên chuỗi đã bỏ dấu. */
    private static final List<Map.Entry<SupportIntent, List<String>>> RULES = List.of(
            Map.entry(
                    SupportIntent.REFUND,
                    List.of(
                            "hoan tien",
                            "hoan ve",
                            "hoan lai",
                            "tra lai tien",
                            "doi ve",
                            "doi ten",
                            "doi suat",
                            "doi ngay",
                            "refund")),
            Map.entry(
                    SupportIntent.INCIDENT,
                    List.of(
                            "chua nhan duoc ve",
                            "khong nhan duoc ve",
                            "chua thay ve",
                            "khong thay ve",
                            "chua co ve",
                            "mat ve",
                            "qr khong quet",
                            "khong quet duoc",
                            "ma qr",
                            "da chuyen khoan",
                            "chuyen khoan roi",
                            "da thanh toan ma",
                            "thanh toan roi ma",
                            "tien da tru",
                            "bi tru tien",
                            "sai ghe",
                            "sai cho",
                            "sai ten",
                            "bi huy",
                            "bi hoan",
                            "doi lich",
                            "doi dia diem")),
            Map.entry(
                    SupportIntent.COMPLAINT,
                    List.of(
                            "khieu nai",
                            "phan anh",
                            "to cao",
                            "lua dao",
                            "that vong",
                            "te qua",
                            "qua te",
                            "khong chap nhan",
                            "bao cao",
                            "complain")),
            Map.entry(
                    SupportIntent.ORDER_STATUS,
                    List.of(
                            "don hang",
                            "ma don",
                            "don cua toi",
                            "don cua minh",
                            "trang thai don",
                            "han thanh toan",
                            "da thanh toan chua",
                            "ve cua toi",
                            "ve cua minh",
                            "order")),
            Map.entry(
                    SupportIntent.BOOKING,
                    List.of(
                            "dat ve",
                            "mua ve",
                            "giu cho",
                            "giu ve",
                            "dat cho",
                            "con ve khong",
                            "con cho khong",
                            "het ve",
                            "book")),
            Map.entry(
                    SupportIntent.EVENT_INFO,
                    List.of(
                            "dien khi nao",
                            "may gio",
                            "ngay nao",
                            "o dau",
                            "dia diem",
                            "gia ve",
                            "bao nhieu tien",
                            "quy dinh",
                            "duoc mang",
                            "do tuoi",
                            "gio mo cua",
                            "suat dien")));

    /** @return {@link SupportIntent#GENERAL} khi không nhận ra gì — kể cả với câu rỗng */
    public static SupportIntent classify(String message) {
        if (message == null || message.isBlank()) {
            return SupportIntent.GENERAL;
        }
        String folded = fold(message);
        for (Map.Entry<SupportIntent, List<String>> rule : RULES) {
            for (String phrase : rule.getValue()) {
                if (folded.contains(phrase)) {
                    return rule.getKey();
                }
            }
        }
        return SupportIntent.GENERAL;
    }

    /**
     * Bỏ dấu tiếng Việt và hạ chữ thường — cùng cách với {@code HandoffIntent}.
     *
     * <p>{@code đ} xử lý riêng vì nó không phải {@code d} có dấu phụ, nên {@link Normalizer} không
     * tách được.
     */
    private static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
