// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.application.command;

import com.nexaticket.payout.application.PayoutErrorCode;
import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutGate;
import com.nexaticket.payout.domain.port.LedgerPort;
import com.nexaticket.payout.domain.port.OrganizationPort;
import com.nexaticket.payout.domain.port.PayoutRepository;
import com.nexaticket.payout.domain.port.ReconciliationRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo lô chi trả, sau khi qua sáu cổng chặn.
 *
 * <p>Chỉ {@code SUPER_ADMIN} gọi được — kiểm ở controller. Đây là công cụ nội bộ; tổ chức không
 * có endpoint nào ở service này (ADR-1010).
 */
@Service
public class CreatePayoutBatchHandler {

    /** Ngày làm việc theo giờ Việt Nam: đối soát là quy trình của người, chạy theo lịch địa phương. */
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PayoutRepository payouts;
    private final LedgerPort ledger;
    private final OrganizationPort organizations;
    private final ReconciliationRepository reconciliation;
    private final Clock clock;

    public CreatePayoutBatchHandler(
            PayoutRepository payouts,
            LedgerPort ledger,
            OrganizationPort organizations,
            ReconciliationRepository reconciliation,
            Clock clock) {
        this.payouts = payouts;
        this.ledger = ledger;
        this.organizations = organizations;
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    /**
     * @param holdPeriodElapsed do người vận hành xác nhận: hệ thống biết ngày diễn nhưng không
     *     biết sự kiện có bị hoãn hay có tranh chấp đang mở
     */
    public record Command(UUID organizationId, long amountVnd, UUID createdBy, boolean holdPeriodElapsed) {}

    public record Result(UUID batchId, long amountVnd, String status) {}

    @Transactional
    public Result handle(Command cmd) {
        var account = payouts.activeAccountOf(cmd.organizationId())
                .orElseThrow(() -> new ApiException(
                        PayoutErrorCode.NO_PAYOUT_ACCOUNT, "No active payout account for this organization"));

        LedgerPort.Balance balance = ledger.balanceOf(cmd.organizationId());
        OrganizationPort.Profile profile = organizations.profileOf(cmd.organizationId());
        LocalDate previousBusinessDay =
                LocalDate.ofInstant(clock.instant(), VIETNAM).minusDays(1);

        var decision = PayoutGate.evaluate(
                cmd.amountVnd(),
                // Trừ phần đang trên đường chuyển: hai lô cùng dựa trên một số dư sẽ chi
                // vượt số dư mà không cổng nào bắt được.
                balance.availableVnd() - payouts.inTransitVnd(cmd.organizationId()),
                balance.receivableVnd(),
                cmd.holdPeriodElapsed(),
                reconciliation.isClosed(previousBusinessDay),
                profile.active(),
                account.accountHolder(),
                profile.legalName());

        if (!decision.allowed()) {
            throw new ApiException(
                    PayoutErrorCode.PAYOUT_BLOCKED,
                    "Payout is blocked by one or more gates",
                    Map.of("blockers", decision.blockers()));
        }

        PayoutBatch batch = PayoutBatch.create(cmd.organizationId(), account.id(), cmd.amountVnd(), cmd.createdBy());
        payouts.save(batch, account.bankBin(), account.accountNumber(), account.accountHolder());

        return new Result(batch.id(), batch.amountVnd(), batch.status().name());
    }
}
