// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.infrastructure.http;

import com.nexaticket.payout.domain.port.LedgerPort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Đọc số dư từ ledger-service.
 *
 * <p>Không cache và không giữ bản sao: số dư là dẫn xuất của sổ cái, và một bản sao ở đây sẽ lệch
 * với sổ cái vào đúng lúc tệ nhất — lúc chi tiền.
 *
 * <p>Ledger hỏng thì <b>ném</b>, không trả số 0. Trả 0 sẽ khiến cổng chặn số dư báo
 * {@code INSUFFICIENT_BALANCE} — một thông báo sai dẫn người vận hành đi tìm nhầm chỗ. Nói thẳng
 * "không đọc được sổ cái" hữu ích hơn nhiều.
 */
@Component
public class LedgerHttpAdapter implements LedgerPort {

    private final RestClient client;

    public LedgerHttpAdapter(@Qualifier("ledgerClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Balance balanceOf(UUID organizationId) {
        try {
            BalanceResponse response = client.get()
                    .uri("/internal/organizations/{id}/balance", organizationId)
                    .retrieve()
                    .body(BalanceResponse.class);
            if (response == null) {
                throw new LedgerUnavailableException("Ledger trả body rỗng", null);
            }
            return new Balance(response.availableVnd(), response.heldVnd(), response.receivableVnd());
        } catch (LedgerUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LedgerUnavailableException("Không đọc được số dư của tổ chức " + organizationId, e);
        }
    }

    record BalanceResponse(long availableVnd, long heldVnd, long receivableVnd) {}
}
