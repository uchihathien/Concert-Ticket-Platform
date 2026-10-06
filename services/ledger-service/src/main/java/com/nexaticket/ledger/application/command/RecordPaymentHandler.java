// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.application.command;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.model.JournalEntry;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi nhận tiền khách chuyển vào tài khoản ký quỹ — bút toán N1 của custodial-funds.md §3.
 *
 * <pre>
 *   Nợ  1010 Tiền ký quỹ                 3.000.000
 *       Có  2011 Phải trả tổ chức (giữ)          2.850.000
 *       Có  4010 Doanh thu hoa hồng                150.000
 * </pre>
 *
 * <p>Hoa hồng ghi nhận <b>ngay lúc bán</b>, không đợi chi trả. Tỷ lệ lấy từ snapshot trong
 * {@code order_items} nên đổi biểu phí sau này không làm sai sổ cũ.
 *
 * <p>Tiền vào tài khoản 2011 (đang giữ) chứ không phải 2012 (khả dụng): tổ chức chỉ rút được sau
 * khi sự kiện kết thúc cộng kỳ giữ tiền.
 */
@Service
public class RecordPaymentHandler {

    private final LedgerRepository ledger;
    private final Clock clock;

    public RecordPaymentHandler(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param commissionBps tỷ lệ hoa hồng theo basis point (500 = 5%)
     * @return id của bút toán đã ghi
     */
    @Transactional
    public UUID handle(Command command) {
        // Consumer retry là chuyện thường ngày; ghi lại lần hai phải là no-op.
        var existing = ledger.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get().id();
        }

        Money gross = command.grossAmount();
        Money commission = command.commission();
        Money organizerShare = gross.minus(commission);

        JournalEntry.Builder builder = JournalEntry.builder()
                .entryType("PAYMENT_CONFIRMED")
                .occurredAt(command.occurredAt())
                .source(JournalEntry.SourceRef.payment(command.paymentAttemptId()))
                .organizationId(command.organizationId())
                .idempotencyKey(command.idempotencyKey())
                .memo(command.memo())
                .createdBy("ledger-service")
                .debit(ledger.platformAccountId(AccountCode.CASH_ESCROW), gross)
                .credit(
                        ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, command.organizationId()),
                        organizerShare);

        // Hoa hồng 0% là hợp lệ (ví dụ sự kiện phi lợi nhuận); khi đó bỏ hẳn dòng thay vì
        // ghi một định khoản 0 đồng vô nghĩa.
        if (!commission.isZero()) {
            builder.credit(ledger.platformAccountId(AccountCode.COMMISSION_REVENUE), commission);
        }

        JournalEntry entry = builder.build();
        ledger.append(entry);
        return entry.id();
    }

    /**
     * @param commission số tiền hoa hồng <b>đã chốt</b>, không tính lại ở đây
     * @param commissionBps tỷ lệ đã áp, chỉ để đối soát và hiển thị
     */
    public record Command(
            UUID paymentAttemptId,
            UUID organizationId,
            Money grossAmount,
            Money commission,
            int commissionBps,
            java.time.Instant occurredAt,
            String idempotencyKey,
            String memo) {

        /** Hoa hồng suy ra từ tỷ lệ trên tổng. Dùng khi người gọi không có sẵn số tiền đã chốt. */
        public static Command of(
                UUID paymentAttemptId, UUID organizationId, Money grossAmount, int commissionBps, Clock clock) {
            return new Command(
                    paymentAttemptId,
                    organizationId,
                    grossAmount,
                    grossAmount.percentOf(commissionBps),
                    commissionBps,
                    clock.instant(),
                    "payment:" + paymentAttemptId,
                    null);
        }

        /**
         * Hoa hồng lấy nguyên từ đơn hàng.
         *
         * <p>Ordering cộng hoa hồng <b>từ từng dòng</b> rồi gửi kèm sự kiện {@code order.paid}, và
         * con số đó mới là con số đã ghi vào {@code orders.commission_vnd}. Tính lại ở đây bằng
         * {@code percentOf} trên tổng cho ra kết quả lệch tới một đồng khi làm tròn — 3 dòng
         * 333.333đ ở 5% cho 16.666×3 = 49.998, còn tính trên tổng 999.999 cho 49.999. Sổ cái phải
         * khớp chứng từ gốc, nên nó nhận số của chứng từ chứ không tự tính lại.
         */
        public static Command ofOrder(
                UUID orderId,
                UUID organizationId,
                Money grossAmount,
                Money commission,
                int commissionBps,
                java.time.Instant paidAt) {
            return new Command(
                    orderId, organizationId, grossAmount, commission, commissionBps, paidAt, "order:" + orderId, null);
        }
    }
}
