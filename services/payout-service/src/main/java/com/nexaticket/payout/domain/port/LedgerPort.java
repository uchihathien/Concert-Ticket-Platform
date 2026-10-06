// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.port;

import java.util.UUID;

/**
 * Đọc số dư từ ledger-service.
 *
 * <p>Payout <b>không</b> giữ bản sao số dư. Số dư là dẫn xuất của sổ cái, và một bản sao ở đây sẽ
 * lệch với sổ cái vào đúng lúc tệ nhất — lúc chi tiền. Chấp nhận một lời gọi mạng để đọc con số
 * đúng, thay vì đọc nhanh một con số có thể sai.
 */
public interface LedgerPort {

    Balance balanceOf(UUID organizationId);

    /**
     * @param availableVnd tài khoản 2012 — đã hết hạn giữ, chi trả được
     * @param heldVnd tài khoản 2011 — đã bán nhưng chưa hết hạn giữ
     * @param receivableVnd tài khoản 1320 — khoản phải thu còn treo
     */
    record Balance(long availableVnd, long heldVnd, long receivableVnd) {}

    class LedgerUnavailableException extends RuntimeException {
        public LedgerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
