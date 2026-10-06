// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.application.command;

import com.nexaticket.payout.application.PayoutErrorCode;
import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.port.PayoutRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Duyệt và xác nhận lô chi trả — nguyên tắc bốn mắt.
 *
 * <p>Người tạo lô không được là người duyệt. Luật này được ép ở ba chỗ, và đó là cố ý vì đây là
 * chỗ tiền rời khỏi hệ thống: ở domain ({@link PayoutBatch#approve}) để báo lỗi rõ cho người dùng,
 * ở CHECK constraint của database để chặn cả script sửa dữ liệu, và ở {@code FOR UPDATE} để hai
 * người bấm cùng lúc không cùng duyệt được.
 */
@Service
public class ApprovePayoutHandler {

    private static final Logger log = LoggerFactory.getLogger(ApprovePayoutHandler.class);

    private final PayoutRepository payouts;
    private final Clock clock;

    public ApprovePayoutHandler(PayoutRepository payouts, Clock clock) {
        this.payouts = payouts;
        this.clock = clock;
    }

    @Transactional
    public void approve(UUID batchId, UUID approver) {
        PayoutBatch batch = lock(batchId);
        try {
            batch.approve(approver, clock.instant());
        } catch (PayoutBatch.SelfApprovalException e) {
            throw new ApiException(
                    PayoutErrorCode.SELF_APPROVAL_FORBIDDEN, "The batch creator cannot approve their own batch");
        } catch (IllegalStateException e) {
            throw new ApiException(PayoutErrorCode.BATCH_NOT_IN_EXPECTED_STATE, e.getMessage());
        }
        payouts.update(batch, clock.instant());
        log.info("Lô chi trả {} được duyệt bởi {}", batchId, approver);
    }

    @Transactional
    public void reject(UUID batchId, String reason) {
        PayoutBatch batch = lock(batchId);
        try {
            batch.reject(reason);
        } catch (IllegalStateException e) {
            throw new ApiException(PayoutErrorCode.BATCH_NOT_IN_EXPECTED_STATE, e.getMessage());
        }
        payouts.update(batch, clock.instant());
    }

    /**
     * Người vận hành xác nhận đã chuyển khoản thật.
     *
     * <p>MVP là chi trả thủ công có phê duyệt: hệ thống tạo lô, người chuyển khoản qua ngân hàng,
     * rồi ghi mã giao dịch vào đây. Tự động hoá bằng API ngân hàng chỉ làm sau khi quy trình thủ
     * công đã chạy ổn định vài tháng — một lỗi trong tự động hoá chi trả là tiền đi mất thật.
     */
    @Transactional
    public void markCompleted(UUID batchId, String bankReference) {
        PayoutBatch batch = lock(batchId);
        try {
            batch.markCompleted(bankReference, clock.instant());
        } catch (IllegalStateException e) {
            throw new ApiException(PayoutErrorCode.BATCH_NOT_IN_EXPECTED_STATE, e.getMessage());
        }
        payouts.update(batch, clock.instant());
        log.info("Lô chi trả {} đã chuyển khoản, mã giao dịch {}", batchId, bankReference);
    }

    private PayoutBatch lock(UUID batchId) {
        return payouts.lockById(batchId)
                .orElseThrow(() -> new ApiException(PayoutErrorCode.BATCH_NOT_FOUND, "Payout batch not found"));
    }
}
