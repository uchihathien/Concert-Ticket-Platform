// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.application.query;

import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đường đọc của sổ cái — <b>chỉ superadmin</b> (ADR-1010).
 *
 * <p>Tổ chức không có endpoint nào ở đây. Họ chỉ thấy số vé và số tiền đã bán, qua
 * {@code sales-summary} của ticketing/ordering. Số dư, hoa hồng, sao kê là dữ liệu của nền tảng.
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueries {

    private final LedgerRepository ledger;

    public LedgerQueries(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * Bảng cân đối thử.
     *
     * <p>{@code balanced = false} là sự cố nghiêm trọng: sổ cái đã sai và mọi con số phái sinh từ
     * nó đều không tin được nữa.
     */
    public TrialBalanceView trialBalance() {
        LedgerRepository.TrialBalance result = ledger.trialBalance();
        return new TrialBalanceView(
                result.totalDebit().amountVnd(), result.totalCredit().amountVnd(), result.isBalanced());
    }

    /** Ba con số tiền của một tổ chức, tách bạch để không ai nhầm "số dư" là con số nào. */
    public OrganizationBalanceView organizationBalance(UUID organizationId) {
        return new OrganizationBalanceView(
                organizationId.toString(),
                balance(AccountCode.ORGANIZER_PAYABLE_HELD, organizationId),
                balance(AccountCode.ORGANIZER_PAYABLE_AVAILABLE, organizationId),
                balance(AccountCode.REFUND_RESERVE, organizationId),
                balance(AccountCode.PAYOUT_IN_TRANSIT, organizationId));
    }

    private long balance(AccountCode code, UUID organizationId) {
        return ledger.balanceOf(ledger.organizationAccountId(code, organizationId))
                .amountVnd();
    }

    public record TrialBalanceView(long totalDebitVnd, long totalCreditVnd, boolean balanced) {}

    /**
     * @param heldVnd tiền đã thu nhưng chưa tới hạn rút
     * @param availableVnd số dư khả dụng — đây mới là con số tổ chức rút được
     * @param refundReserveVnd phần giữ lại phòng khách đòi hoàn
     * @param inTransitVnd đã giữ chỗ để chi trả, ngân hàng chưa xác nhận
     */
    public record OrganizationBalanceView(
            String organizationId, long heldVnd, long availableVnd, long refundReserveVnd, long inTransitVnd) {}
}
