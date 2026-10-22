// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Đường đọc sổ cái: bốn số dư của tổ chức không được lẫn tài khoản, bảng cân đối phải báo lệch. */
@ExtendWith(MockitoExtension.class)
class LedgerQueriesTest {

    @Mock
    LedgerRepository ledger;

    @Test
    @DisplayName("Bảng cân đối thử cân: trả hai tổng và balanced=true")
    void can_doi_thu_can() {
        when(ledger.trialBalance())
                .thenReturn(new LedgerRepository.TrialBalance(Money.ofVnd(5_000_000), Money.ofVnd(5_000_000)));

        assertThat(new LedgerQueries(ledger).trialBalance())
                .isEqualTo(new LedgerQueries.TrialBalanceView(5_000_000, 5_000_000, true));
    }

    @Test
    @DisplayName("Bảng cân đối thử lệch một đồng: balanced=false — cảnh báo mức cao nhất không được nuốt")
    void can_doi_thu_lech() {
        when(ledger.trialBalance())
                .thenReturn(new LedgerRepository.TrialBalance(Money.ofVnd(5_000_001), Money.ofVnd(5_000_000)));

        assertThat(new LedgerQueries(ledger).trialBalance().balanced()).isFalse();
    }

    @Test
    @DisplayName("Số dư tổ chức: mỗi con số đọc từ ĐÚNG tài khoản 2011/2012/2013/2040 của chính tổ chức đó")
    void so_du_to_chuc_dung_tai_khoan() {
        UUID org = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        UUID reserve = UUID.randomUUID();
        UUID transit = UUID.randomUUID();
        when(ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org))
                .thenReturn(held);
        when(ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_AVAILABLE, org))
                .thenReturn(available);
        when(ledger.organizationAccountId(AccountCode.REFUND_RESERVE, org)).thenReturn(reserve);
        when(ledger.organizationAccountId(AccountCode.PAYOUT_IN_TRANSIT, org)).thenReturn(transit);
        // Bốn số khác nhau để một lần tráo tài khoản lộ ra ngay.
        when(ledger.balanceOf(held)).thenReturn(Money.ofVnd(1));
        when(ledger.balanceOf(available)).thenReturn(Money.ofVnd(20));
        when(ledger.balanceOf(reserve)).thenReturn(Money.ofVnd(300));
        when(ledger.balanceOf(transit)).thenReturn(Money.ofVnd(4_000));

        LedgerQueries.OrganizationBalanceView view = new LedgerQueries(ledger).organizationBalance(org);

        assertThat(view).isEqualTo(new LedgerQueries.OrganizationBalanceView(org.toString(), 1, 20, 300, 4_000));
    }
}
