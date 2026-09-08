// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.port;

import com.nexaticket.payout.domain.model.PayoutBatch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository {

    void save(PayoutBatch batch, String bankBin, String accountNumber, String accountHolder);

    /**
     * Đọc và <b>khoá</b> lô.
     *
     * <p>{@code FOR UPDATE}: hai superadmin cùng bấm duyệt một lô trong cùng một giây là chuyện
     * có thật, và đọc-rồi-ghi sẽ cho cả hai cùng thấy PENDING_APPROVAL rồi cùng ghi APPROVED —
     * nguyên tắc bốn mắt vẫn đúng nhưng lô bị duyệt hai lần và audit trail chỉ giữ người sau.
     */
    Optional<PayoutBatch> lockById(UUID batchId);

    void update(PayoutBatch batch, Instant at);

    List<PayoutBatch> pendingApproval(int limit);

    Optional<PayoutAccount> activeAccountOf(UUID organizationId);

    void saveAccount(PayoutAccount account);

    /** Tổng tiền đang trên đường chuyển của một tổ chức — lô đã duyệt nhưng chưa xác nhận. */
    long inTransitVnd(UUID organizationId);

    record PayoutAccount(
            UUID id,
            UUID organizationId,
            String bankBin,
            String bankName,
            String accountNumber,
            String accountHolder,
            boolean active) {}
}
