// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.application.command.RecordPaymentHandler;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import com.nexaticket.ledger.support.LedgerTestBase;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bút toán N1 — ghi nhận tiền khách chuyển vào (custodial-funds.md §3). */
class RecordPaymentIT extends LedgerTestBase {

    private static final int COMMISSION_5_PERCENT = 500;

    @Autowired
    RecordPaymentHandler recordPayment;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    Clock clock;

    @Test
    @DisplayName("Đơn 3.000.000 với hoa hồng 5%: tổ chức 2.850.000, nền tảng 150.000")
    void ghi_nhan_thanh_toan_tach_hoa_hong_ngay_luc_ban() {
        UUID org = UUID.randomUUID();
        UUID cash = ledger.platformAccountId(AccountCode.CASH_ESCROW);
        UUID revenue = ledger.platformAccountId(AccountCode.COMMISSION_REVENUE);
        Money cashBefore = ledger.balanceOf(cash);
        Money revenueBefore = ledger.balanceOf(revenue);

        recordPayment.handle(RecordPaymentHandler.Command.of(
                UUID.randomUUID(), org, Money.ofVnd(3_000_000), COMMISSION_5_PERCENT, clock));

        UUID payableHeld = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);

        assertThat(ledger.balanceOf(cash)).isEqualTo(cashBefore.plus(Money.ofVnd(3_000_000)));
        assertThat(ledger.balanceOf(payableHeld)).isEqualTo(Money.ofVnd(2_850_000));
        assertThat(ledger.balanceOf(revenue)).isEqualTo(revenueBefore.plus(Money.ofVnd(150_000)));

        // Tiền vào 2011 (đang giữ), KHÔNG vào 2012 (khả dụng): chưa tới ngày diễn thì chưa rút được.
        UUID payableAvailable = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_AVAILABLE, org);
        assertThat(ledger.balanceOf(payableAvailable)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("Hoa hồng 0% vẫn ghi được, không sinh định khoản 0 đồng")
    void hoa_hong_khong_phan_tram() {
        UUID org = UUID.randomUUID();

        recordPayment.handle(RecordPaymentHandler.Command.of(UUID.randomUUID(), org, Money.ofVnd(1_000_000), 0, clock));

        UUID payableHeld = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);
        assertThat(ledger.balanceOf(payableHeld)).isEqualTo(Money.ofVnd(1_000_000));
        assertThat(ledger.trialBalance().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("Webhook gửi lại nhiều lần chỉ ghi sổ một lần")
    void ghi_lai_cung_mot_thanh_toan_la_no_op() {
        UUID org = UUID.randomUUID();
        UUID paymentAttempt = UUID.randomUUID();
        var command = RecordPaymentHandler.Command.of(
                paymentAttempt, org, Money.ofVnd(2_000_000), COMMISSION_5_PERCENT, clock);

        UUID first = recordPayment.handle(command);
        UUID second = recordPayment.handle(command);
        UUID third = recordPayment.handle(command);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);

        UUID payableHeld = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);
        assertThat(ledger.balanceOf(payableHeld))
                .as("ghi ba lần nhưng số dư chỉ tăng một lần")
                .isEqualTo(Money.ofVnd(1_900_000));
    }

    @Test
    @DisplayName("200 thanh toán đồng thời: sổ cái vẫn cân, số dư khớp từng đồng")
    void hai_tram_thanh_toan_dong_thoi() throws Exception {
        UUID org = UUID.randomUUID();
        int count = 200;
        Money each = Money.ofVnd(1_000_000);

        List<Callable<UUID>> jobs = java.util.stream.IntStream.range(0, count)
                .<Callable<UUID>>mapToObj(i -> () -> recordPayment.handle(
                        RecordPaymentHandler.Command.of(UUID.randomUUID(), org, each, COMMISSION_5_PERCENT, clock)))
                .toList();

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<UUID> future : pool.invokeAll(jobs)) {
                future.get();
            }
        }

        UUID payableHeld = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);

        // 200 × 950.000 = 190.000.000 — không thiếu, không thừa một đồng.
        assertThat(ledger.balanceOf(payableHeld)).isEqualTo(Money.ofVnd(190_000_000));
        assertThat(ledger.trialBalance().isBalanced())
                .as("bảng cân đối thử vẫn cân sau 200 giao dịch đồng thời")
                .isTrue();
    }
}
