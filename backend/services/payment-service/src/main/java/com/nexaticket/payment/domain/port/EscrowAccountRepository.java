// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.EscrowBankAccount;
import java.util.List;
import java.util.Optional;

public interface EscrowAccountRepository {

    /** Tài khoản ký quỹ đang được ưu tiên nhận tiền. Rỗng nghĩa là chưa ai cấu hình. */
    Optional<EscrowBankAccount> preferred();

    List<EscrowBankAccount> all();

    void save(EscrowBankAccount account);
}
