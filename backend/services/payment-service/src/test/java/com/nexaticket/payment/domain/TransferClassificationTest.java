// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.model.EscrowBankAccount;
import com.nexaticket.payment.domain.model.IntentStatus;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.TransferClassification;
import com.nexaticket.payment.domain.model.WebhookOutcome;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ma trận 8 nhánh webhook (BRAINSTORM §4.2) — spike bắt buộc thứ ba.
 *
 * <p>Chạy được như test thuần vì {@code TransferClassification} là hàm thuần: không database,
 * không HTTP, không {@code Instant.now()}. Toàn bộ luật nghiệp vụ về tiền của hệ thống nằm ở đây
 * và kiểm được trong vài mili giây.
 *
 * <p><b>Bất biến bao trùm:</b> {@code REJECTED} chỉ xuất hiện ở đúng một nhánh — giao dịch tiền
 * ra, tức là chắc chắn không có đồng nào về tài khoản ta. Mọi nhánh còn lại đều có tiền thật, nên
 * không nhánh nào được phép kết luận "bỏ qua".
 */
class TransferClassificationTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final String BIN = "970422";
    private static final String ACCOUNT = "0123456789";
    private static final long AMOUNT = 3_000_000L;

    @Test
    @DisplayName("Nhánh 1 — giao dịch tiền ra: REJECTED (nhánh DUY NHẤT được phép)")
    void nhanh_1_tien_ra() {
        var transfer = new BankTransfer("tx-1", "CK " + reference(), -500_000L, BIN, ACCOUNT);

        assertThat(classify(transfer, intent(IntentStatus.PENDING)).outcome()).isEqualTo(WebhookOutcome.REJECTED);
    }

    @Test
    @DisplayName("Nhánh 2 — không đọc được mã tham chiếu: MANUAL_REVIEW vì tiền đã về")
    void nhanh_2_khong_doc_duoc_ma() {
        var transfer = new BankTransfer("tx-2", "THANH TOAN VE", AMOUNT, BIN, ACCOUNT);

        var verdict = classify(transfer, Optional.empty());
        assertThat(verdict.outcome()).isEqualTo(WebhookOutcome.MANUAL_REVIEW);
        assertThat(verdict.note()).contains("Không đọc được mã tham chiếu");
    }

    @Test
    @DisplayName("Nhánh 3 — mã đúng dạng nhưng không thuộc hệ thống: MANUAL_REVIEW")
    void nhanh_3_ma_khong_thuoc_he_thong() {
        var transfer = new BankTransfer("tx-3", "CK NTZZZZZZZZ", AMOUNT, BIN, ACCOUNT);

        assertThat(classify(transfer, Optional.empty()).outcome()).isEqualTo(WebhookOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Nhánh 4 — sai tài khoản nhận: MANUAL_REVIEW")
    void nhanh_4_sai_tai_khoan_nhan() {
        // So với tài khoản ĐÃ IN TRÊN MÃ QR, không phải tài khoản ký quỹ hiện hành.
        var transfer = new BankTransfer("tx-4", "CK " + reference(), AMOUNT, "970418", "9999999999");

        var verdict = classify(transfer, intent(IntentStatus.PENDING));
        assertThat(verdict.outcome()).isEqualTo(WebhookOutcome.MANUAL_REVIEW);
        assertThat(verdict.note()).contains("tài khoản khác");
    }

    @Test
    @DisplayName("Nhánh 5 — thiếu tiền: MANUAL_REVIEW, ghi rõ thiếu bao nhiêu")
    void nhanh_5_thieu_tien() {
        var transfer = new BankTransfer("tx-5", "CK " + reference(), AMOUNT - 100_000, BIN, ACCOUNT);

        var verdict = classify(transfer, intent(IntentStatus.PENDING));
        assertThat(verdict.outcome()).isEqualTo(WebhookOutcome.MANUAL_REVIEW);
        assertThat(verdict.note()).contains("Thiếu 100000");
    }

    @Test
    @DisplayName("Nhánh 6 — thừa tiền: CONFIRMED, không chặn khách vào cửa vì chuyển dư")
    void nhanh_6_thua_tien() {
        var transfer = new BankTransfer("tx-6", "CK " + reference(), AMOUNT + 50_000, BIN, ACCOUNT);

        var verdict = classify(transfer, intent(IntentStatus.PENDING));
        assertThat(verdict.outcome()).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(verdict.note()).contains("Thừa 50000");
    }

    @Test
    @DisplayName("Nhánh 7 — tiền về sau khi đơn hết hạn: MANUAL_REVIEW, ghế có thể đã bán")
    void nhanh_7_den_sau_khi_het_han() {
        var transfer = new BankTransfer("tx-7", "CK " + reference(), AMOUNT, BIN, ACCOUNT);
        var expired = intentExpiringAt(NOW.minus(1, ChronoUnit.MINUTES), IntentStatus.PENDING);

        var verdict = TransferClassification.classify(transfer, expired, NOW);
        assertThat(verdict.outcome()).isEqualTo(WebhookOutcome.MANUAL_REVIEW);
        assertThat(verdict.note()).contains("hết hạn");
    }

    @Test
    @DisplayName("Nhánh 8 — khớp, đủ tiền, còn hạn: CONFIRMED không kèm ghi chú")
    void nhanh_8_duong_thanh_cong() {
        var transfer = new BankTransfer("tx-8", "CK " + reference(), AMOUNT, BIN, ACCOUNT);

        var verdict = classify(transfer, intent(IntentStatus.PENDING));
        assertThat(verdict.outcome()).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(verdict.note()).isNull();
    }

    @Test
    @DisplayName("Đơn đã CONFIRMED trước đó: DUPLICATE, không xác nhận lần hai")
    void da_xac_nhan_truoc_do() {
        var transfer = new BankTransfer("tx-9", "CK " + reference(), AMOUNT, BIN, ACCOUNT);

        assertThat(classify(transfer, intent(IntentStatus.CONFIRMED)).outcome()).isEqualTo(WebhookOutcome.DUPLICATE);
    }

    @Test
    @DisplayName("Đơn đã CANCELLED mà tiền vẫn về: MANUAL_REVIEW, không phải REJECTED")
    void don_da_huy_ma_tien_van_ve() {
        // Khách huỷ đơn rồi mới bấm chuyển khoản. Tiền đã vào tài khoản ta nên phải có người
        // xử lý — đánh REJECTED ở đây là tạo ra một giao dịch vô chủ.
        var transfer = new BankTransfer("tx-10", "CK " + reference(), AMOUNT, BIN, ACCOUNT);

        assertThat(classify(transfer, intent(IntentStatus.CANCELLED)).outcome())
                .isEqualTo(WebhookOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Bất biến: mọi nhánh có tiền vào đều KHÔNG bao giờ là REJECTED")
    void moi_nhanh_co_tien_deu_khong_bi_reject() {
        // Đây là bất biến trung tâm của toàn bộ service. Nếu ai đó thêm một nhánh mới và trót
        // đánh REJECTED, test này đỏ trước khi tiền của khách biến mất.
        var cases = java.util.List.of(
                new BankTransfer("a", "khong co ma", AMOUNT, BIN, ACCOUNT),
                new BankTransfer("b", "CK NTZZZZZZZZ", AMOUNT, BIN, ACCOUNT),
                new BankTransfer("c", "CK " + reference(), AMOUNT, "970418", "999"),
                new BankTransfer("d", "CK " + reference(), 1L, BIN, ACCOUNT),
                new BankTransfer("e", "CK " + reference(), AMOUNT * 2, BIN, ACCOUNT));

        for (BankTransfer transfer : cases) {
            var withIntent = classify(transfer, intent(IntentStatus.PENDING));
            var withoutIntent = classify(transfer, Optional.empty());
            assertThat(withIntent.outcome()).isNotEqualTo(WebhookOutcome.REJECTED);
            assertThat(withoutIntent.outcome()).isNotEqualTo(WebhookOutcome.REJECTED);
        }
    }

    // --- dựng dữ liệu ---

    private static TransferClassification.Verdict classify(BankTransfer transfer, Optional<PaymentIntent> intent) {
        return TransferClassification.classify(transfer, intent, NOW);
    }

    private static String reference() {
        return INTENT.reference().value();
    }

    private static final PaymentIntent INTENT = PaymentIntent.open(
            UUID.randomUUID(),
            UUID.randomUUID(),
            AMOUNT,
            new EscrowBankAccount(UUID.randomUUID(), BIN, "MB Bank", ACCOUNT, "NEXATICKET", true, true),
            NOW.plus(15, ChronoUnit.MINUTES));

    private static Optional<PaymentIntent> intent(IntentStatus status) {
        return Optional.of(withStatus(INTENT.expiresAt(), status));
    }

    private static Optional<PaymentIntent> intentExpiringAt(Instant expiresAt, IntentStatus status) {
        return Optional.of(withStatus(expiresAt, status));
    }

    private static PaymentIntent withStatus(Instant expiresAt, IntentStatus status) {
        return PaymentIntent.rehydrate(
                INTENT.id(),
                INTENT.orderId(),
                INTENT.organizationId(),
                INTENT.amountVnd(),
                INTENT.reference().value(),
                INTENT.bankAccountId(),
                BIN,
                ACCOUNT,
                INTENT.vietQrPayload(),
                expiresAt,
                status,
                status == IntentStatus.CONFIRMED ? NOW : null);
    }
}
