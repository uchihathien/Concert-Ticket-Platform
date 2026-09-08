// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.time.Instant;
import java.util.Optional;

/**
 * Ma trận phân loại giao dịch ngân hàng (BRAINSTORM §4.2) — <b>hàm thuần</b>.
 *
 * <p>Cố ý không chạm database, không chạm HTTP, không có {@code Instant.now()}. Đây là nơi chứa
 * toàn bộ luật nghiệp vụ về tiền của hệ thống, và nó phải kiểm được bằng test bảng chạy trong vài
 * mili giây — chứ không phải bằng cách dựng webhook giả và soi cơ sở dữ liệu.
 *
 * <table>
 *   <caption>Tám nhánh</caption>
 *   <tr><th>Tình huống</th><th>Tiền đã về?</th><th>Kết luận</th></tr>
 *   <tr><td>Giao dịch tiền ra</td><td>Không</td><td>REJECTED</td></tr>
 *   <tr><td>Không đọc được mã tham chiếu</td><td><b>Có</b></td><td>MANUAL_REVIEW</td></tr>
 *   <tr><td>Mã không thuộc hệ thống</td><td><b>Có</b></td><td>MANUAL_REVIEW</td></tr>
 *   <tr><td>Sai tài khoản nhận</td><td>Có</td><td>MANUAL_REVIEW</td></tr>
 *   <tr><td>Thiếu tiền</td><td>Có</td><td>MANUAL_REVIEW</td></tr>
 *   <tr><td>Thừa tiền</td><td>Có</td><td>CONFIRMED + ghi chú chênh lệch</td></tr>
 *   <tr><td>Đơn đã hết hạn</td><td>Có</td><td>MANUAL_REVIEW</td></tr>
 *   <tr><td>Khớp, đủ tiền, còn hạn</td><td>Có</td><td>CONFIRMED</td></tr>
 * </table>
 */
public final class TransferClassification {

    private TransferClassification() {}

    /**
     * @param intent intent khớp mã tham chiếu, rỗng nếu không tra ra
     * @param now thời điểm xử lý, truyền vào để test tua được
     */
    public static Verdict classify(BankTransfer transfer, Optional<PaymentIntent> intent, Instant now) {
        // Nhánh DUY NHẤT dám kết luận REJECTED: không có đồng nào vào tài khoản ta,
        // nên không có tiền của ai để bỏ rơi.
        if (!transfer.isIncoming()) {
            return new Verdict(WebhookOutcome.REJECTED, "Giao dịch tiền ra, không phải thanh toán");
        }

        if (PaymentReference.findIn(transfer.rawContent()).isEmpty()) {
            return new Verdict(
                    WebhookOutcome.MANUAL_REVIEW, "Không đọc được mã tham chiếu trong nội dung chuyển khoản");
        }
        if (intent.isEmpty()) {
            return new Verdict(WebhookOutcome.MANUAL_REVIEW, "Mã tham chiếu không thuộc hệ thống");
        }

        PaymentIntent open = intent.get();

        // So với tài khoản ĐÃ IN TRÊN MÃ QR, không phải tài khoản ký quỹ hiện hành: nền tảng
        // có thể đã đổi tài khoản sau khi khách nhận mã.
        if (!open.matchesReceivingAccount(transfer)) {
            return new Verdict(WebhookOutcome.MANUAL_REVIEW, "Tiền về tài khoản khác với tài khoản trên mã QR");
        }
        if (open.status() == IntentStatus.CONFIRMED) {
            return new Verdict(WebhookOutcome.DUPLICATE, "Đơn đã được xác nhận thanh toán trước đó");
        }
        if (transfer.amountVnd() < open.amountVnd()) {
            return new Verdict(
                    WebhookOutcome.MANUAL_REVIEW, "Thiếu %d đồng".formatted(open.amountVnd() - transfer.amountVnd()));
        }

        // Hết hạn được kiểm SAU khi đã biết tiền khớp: một đơn hết hạn mà tiền đã về vẫn là
        // tiền của khách, và ghế có thể đã bán cho người khác (ADR-0015) — chỉ người mới
        // quyết được là hoàn tiền hay xếp chỗ khác.
        if (open.status() == IntentStatus.EXPIRED || open.isExpiredAt(now)) {
            return new Verdict(WebhookOutcome.MANUAL_REVIEW, "Tiền về sau khi đơn đã hết hạn thanh toán");
        }
        if (open.status() != IntentStatus.PENDING) {
            return new Verdict(
                    WebhookOutcome.MANUAL_REVIEW, "Đơn ở trạng thái " + open.status() + ", không nhận thanh toán");
        }

        if (transfer.amountVnd() > open.amountVnd()) {
            // Không chặn khách vào cửa vì họ chuyển dư. Phần dư ghi chú lại để đối soát và
            // hoàn sau, còn vé thì phát ngay.
            return new Verdict(
                    WebhookOutcome.CONFIRMED,
                    "Thừa %d đồng, cần hoàn lại".formatted(transfer.amountVnd() - open.amountVnd()));
        }
        return new Verdict(WebhookOutcome.CONFIRMED, null);
    }

    /**
     * @param note lý do, luôn có với MANUAL_REVIEW để người đối soát không phải đoán
     */
    public record Verdict(WebhookOutcome outcome, String note) {

        public boolean needsHumanReview() {
            return outcome == WebhookOutcome.MANUAL_REVIEW;
        }
    }
}
