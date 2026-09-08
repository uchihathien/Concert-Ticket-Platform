// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Một lô chi trả.
 *
 * <p>Nguyên tắc bốn mắt nằm ở {@link #approve}: người tạo lô không được là người duyệt. Luật này
 * được ép ở <b>cả hai chỗ</b> — ở đây để báo lỗi rõ ràng cho người dùng, và bằng CHECK constraint
 * trong database để chặn cả những đường không đi qua code này.
 */
public final class PayoutBatch {

    private final UUID id;
    private final UUID organizationId;
    private final UUID payoutAccountId;
    private final long amountVnd;
    private final UUID createdBy;

    private PayoutStatus status;
    private UUID approvedBy;
    private Instant approvedAt;
    private String bankReference;
    private Instant completedAt;
    private String rejectedReason;

    private PayoutBatch(
            UUID id, UUID organizationId, UUID payoutAccountId, long amountVnd, UUID createdBy, PayoutStatus status) {
        if (amountVnd <= 0) {
            throw new IllegalArgumentException("Số tiền chi trả phải dương");
        }
        this.id = id;
        this.organizationId = organizationId;
        this.payoutAccountId = payoutAccountId;
        this.amountVnd = amountVnd;
        this.createdBy = createdBy;
        this.status = status;
    }

    public static PayoutBatch create(UUID organizationId, UUID payoutAccountId, long amountVnd, UUID createdBy) {
        return new PayoutBatch(
                UUID.randomUUID(),
                organizationId,
                payoutAccountId,
                amountVnd,
                createdBy,
                PayoutStatus.PENDING_APPROVAL);
    }

    /**
     * Dựng lại từ database.
     *
     * <p>{@code approvedAt} phải được nạp cùng {@code approvedBy}, không được bỏ qua. Bỏ qua thì
     * lần ghi tiếp theo sẽ có người duyệt mà không có thời điểm duyệt, và
     * {@code ck_approved_pair} sẽ chặn — hoặc tệ hơn, nếu không có ràng buộc đó thì audit trail
     * của một lần chi trả mất mốc thời gian mà không ai biết.
     */
    public static PayoutBatch rehydrate(
            UUID id,
            UUID organizationId,
            UUID payoutAccountId,
            long amountVnd,
            UUID createdBy,
            PayoutStatus status,
            UUID approvedBy,
            Instant approvedAt) {
        PayoutBatch batch = new PayoutBatch(id, organizationId, payoutAccountId, amountVnd, createdBy, status);
        batch.approvedBy = approvedBy;
        batch.approvedAt = approvedAt;
        return batch;
    }

    /**
     * Duyệt lô.
     *
     * @throws SelfApprovalException khi người duyệt chính là người tạo — nguyên tắc bốn mắt
     * @throws IllegalStateException khi lô không ở trạng thái chờ duyệt
     */
    public void approve(UUID approver, Instant now) {
        if (status != PayoutStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Lô không ở trạng thái chờ duyệt: " + status);
        }
        if (createdBy.equals(approver)) {
            throw new SelfApprovalException(id);
        }
        status = PayoutStatus.APPROVED;
        approvedBy = approver;
        approvedAt = now;
    }

    /**
     * Xác nhận đã chuyển khoản thật.
     *
     * <p>Chỉ lô đã duyệt mới xác nhận được: bỏ qua bước duyệt nghĩa là một người tự tạo lô rồi tự
     * đánh dấu đã chuyển, và nguyên tắc bốn mắt biến mất.
     */
    public void markCompleted(String bankReference, Instant now) {
        if (status != PayoutStatus.APPROVED) {
            throw new IllegalStateException("Chỉ lô đã duyệt mới xác nhận được: " + status);
        }
        this.status = PayoutStatus.COMPLETED;
        this.bankReference = bankReference;
        this.completedAt = now;
    }

    public void reject(String reason) {
        if (status != PayoutStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Lô không ở trạng thái chờ duyệt: " + status);
        }
        this.status = PayoutStatus.REJECTED;
        this.rejectedReason = reason;
    }

    /** Người tạo lô tự duyệt lô của mình — chặn ở cả domain lẫn CHECK constraint. */
    public static class SelfApprovalException extends RuntimeException {
        public SelfApprovalException(UUID batchId) {
            super("Người tạo lô " + batchId + " không được tự duyệt");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID payoutAccountId() {
        return payoutAccountId;
    }

    public long amountVnd() {
        return amountVnd;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public PayoutStatus status() {
        return status;
    }

    public UUID approvedBy() {
        return approvedBy;
    }

    public Instant approvedAt() {
        return approvedAt;
    }

    public String bankReference() {
        return bankReference;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String rejectedReason() {
        return rejectedReason;
    }
}
