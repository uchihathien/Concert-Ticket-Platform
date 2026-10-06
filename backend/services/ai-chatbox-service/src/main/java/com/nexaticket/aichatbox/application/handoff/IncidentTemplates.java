// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.domain.model.IncidentKind;
import java.util.List;
import java.util.Map;

/**
 * Khung mẫu phiếu sự cố — mỗi loại sự cố có một cách viết lý do và một danh sách việc người trực
 * cần làm.
 *
 * <h2>Vì sao có khung mẫu</h2>
 *
 * <p>Không có nó thì lý do của phiếu là bất cứ thứ gì mô hình viết: "khách gặp vấn đề với vé", và
 * người trực phải mở cả hội thoại để biết vấn đề gì. Khung mẫu ép lý do về một hình dạng cố định —
 * loại sự cố, mã đơn, rồi mới tới mô tả — nên hàng đợi đọc lướt được, và một người trực lần đầu
 * nhận loại sự cố này có ngay danh sách việc phải kiểm.
 *
 * <p>Danh sách việc là cho <b>người</b>, không đưa vào prompt: mô hình không có tool để "kiểm
 * webhook payOS", và cho nó đọc danh sách ấy chỉ để nó hứa làm những việc không làm được.
 */
public final class IncidentTemplates {

    private IncidentTemplates() {}

    /**
     * @param headline tiền tố của lý do phiếu, người trực nhìn thấy ngay ở hàng đợi
     * @param checklist việc người trực cần kiểm, theo thứ tự nên làm
     */
    public record Template(IncidentKind kind, String headline, List<String> checklist) {
        public Template {
            checklist = List.copyOf(checklist);
        }
    }

    private static final Map<IncidentKind, Template> TEMPLATES = Map.of(
            IncidentKind.TICKET_NOT_RECEIVED,
                    new Template(
                            IncidentKind.TICKET_NOT_RECEIVED,
                            "Chưa nhận được vé",
                            List.of(
                                    "Kiểm trạng thái đơn ở ordering: PAID chưa, paidAt lúc nào",
                                    "Kiểm ticketing đã phát vé cho đơn chưa (ví vé của khách)",
                                    "Nếu PAID mà chưa có vé: phát lại vé, rồi báo khách mở lại ví vé",
                                    "Nếu chưa PAID: hướng khách kiểm lại chuyển khoản, xem sự cố thanh toán")),
            IncidentKind.PAYMENT_NOT_CONFIRMED,
                    new Template(
                            IncidentKind.PAYMENT_NOT_CONFIRMED,
                            "Thanh toán chưa được ghi nhận",
                            List.of(
                                    "Xin khách ảnh chụp giao dịch: số tiền, thời điểm, nội dung chuyển khoản",
                                    "Đối chiếu mã tham chiếu với webhook payOS của đơn",
                                    "Đơn đã EXPIRED/CANCELLED mà tiền vào: chuyển MANUAL_REVIEW, xin ý kiến hoàn",
                                    "Khớp được: xác nhận thanh toán bằng tay qua đường nội bộ của ordering")),
            IncidentKind.QR_NOT_SCANNABLE,
                    new Template(
                            IncidentKind.QR_NOT_SCANNABLE,
                            "Mã QR không quét được",
                            List.of(
                                    "Kiểm vé còn hiệu lực ở ticketing: chưa bị thu hồi, chưa check-in",
                                    "Hỏi khách đang mở ảnh chụp màn hình hay mở trực tiếp trong ví vé",
                                    "Liên hệ nhân viên soát vé tại cổng để nhập mã vé bằng tay",
                                    "Nếu vé bị trùng: tra lịch sử phát hành, xử lý theo quy trình gian lận")),
            IncidentKind.WRONG_TICKET_DETAILS,
                    new Template(
                            IncidentKind.WRONG_TICKET_DETAILS,
                            "Vé sai thông tin",
                            List.of(
                                    "So vé đã phát với các mục của đơn: khu, ghế, hạng vé, suất",
                                    "Khớp với đơn: giải thích cho khách và xác nhận lại lúc đặt",
                                    "Không khớp: thu hồi vé sai, phát lại đúng, ghi rõ lý do vào phiếu")),
            IncidentKind.EVENT_CHANGED,
                    new Template(
                            IncidentKind.EVENT_CHANGED,
                            "Sự kiện thay đổi hoặc huỷ",
                            List.of(
                                    "Xác nhận với ban tổ chức: đổi lịch, đổi địa điểm, hay huỷ",
                                    "Đọc chính sách hoàn vé của sự kiện (event_rules) để nói đúng với khách",
                                    "Huỷ: hướng khách quy trình hoàn tiền; đổi: xác nhận vé vẫn còn hiệu lực")),
            IncidentKind.OTHER,
                    new Template(
                            IncidentKind.OTHER,
                            "Sự cố khác",
                            List.of("Đọc mô tả của khách và hội thoại, xếp lại vào đúng loại nếu có thể")));

    public static Template forKind(IncidentKind kind) {
        return TEMPLATES.get(kind);
    }

    /**
     * Lý do phiếu theo khung: {@code [Sự cố] <loại> · đơn <mã>: <mô tả>}.
     *
     * <p>Mã đơn đứng trước mô tả vì người trực tra đơn trước khi đọc mô tả — và với hàng đợi 40
     * phiếu, phần đầu của mỗi dòng là phần duy nhất họ đọc.
     *
     * @param orderNumber {@code null} khi sự cố không gắn với đơn nào
     * @param description lời khách, do mô hình chép lại — cắt bớt vì đây là một dòng, không phải
     *     cả hội thoại
     */
    public static String composeReason(IncidentKind kind, String orderNumber, String description) {
        StringBuilder sb = new StringBuilder("[Sự cố] ").append(forKind(kind).headline());
        if (orderNumber != null && !orderNumber.isBlank()) {
            sb.append(" · đơn ").append(orderNumber.strip());
        }
        String text = description == null ? "" : description.strip();
        if (!text.isEmpty()) {
            sb.append(": ").append(text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION) + "…");
        }
        return sb.toString();
    }

    /** Mô tả dài hơn thế thuộc về hội thoại, nơi người trực vẫn đọc được trọn. */
    private static final int MAX_DESCRIPTION = 300;
}
