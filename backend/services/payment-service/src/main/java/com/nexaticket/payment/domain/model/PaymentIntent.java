// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Yêu cầu thanh toán của một đơn hàng. */
public final class PaymentIntent {

    private final UUID id;
    private final UUID orderId;
    private final UUID organizationId;
    private final long amountVnd;
    private final PaymentReference reference;
    private final UUID bankAccountId;
    private final String bankBin;
    private final String bankAccountNumber;
    private final String vietQrPayload;
    private final Instant expiresAt;

    private IntentStatus status;
    private Instant confirmedAt;

    @SuppressWarnings("java:S107") // Rehydrate từ database cần đủ cột; gom thành DTO chỉ đổi chỗ đặt tham số.
    private PaymentIntent(
            UUID id,
            UUID orderId,
            UUID organizationId,
            long amountVnd,
            PaymentReference reference,
            UUID bankAccountId,
            String bankBin,
            String bankAccountNumber,
            String vietQrPayload,
            Instant expiresAt,
            IntentStatus status,
            Instant confirmedAt) {
        this.id = id;
        this.orderId = orderId;
        this.organizationId = organizationId;
        this.amountVnd = amountVnd;
        this.reference = reference;
        this.bankAccountId = bankAccountId;
        this.bankBin = bankBin;
        this.bankAccountNumber = bankAccountNumber;
        this.vietQrPayload = vietQrPayload;
        this.expiresAt = expiresAt;
        this.status = status;
        this.confirmedAt = confirmedAt;
    }

    public static PaymentIntent open(
            UUID orderId, UUID organizationId, long amountVnd, EscrowBankAccount account, Instant expiresAt) {
        PaymentReference reference = PaymentReference.generate();
        return new PaymentIntent(
                UUID.randomUUID(),
                orderId,
                organizationId,
                amountVnd,
                reference,
                account.id(),
                account.bankBin(),
                account.accountNumber(),
                VietQr.build(account.bankBin(), account.accountNumber(), amountVnd, reference),
                expiresAt,
                IntentStatus.PENDING,
                null);
    }

    @SuppressWarnings("java:S107")
    public static PaymentIntent rehydrate(
            UUID id,
            UUID orderId,
            UUID organizationId,
            long amountVnd,
            String reference,
            UUID bankAccountId,
            String bankBin,
            String bankAccountNumber,
            String vietQrPayload,
            Instant expiresAt,
            IntentStatus status,
            Instant confirmedAt) {
        return new PaymentIntent(
                id,
                orderId,
                organizationId,
                amountVnd,
                new PaymentReference(reference),
                bankAccountId,
                bankBin,
                bankAccountNumber,
                vietQrPayload,
                expiresAt,
                status,
                confirmedAt);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Tiền về đúng tài khoản đã in trên mã QR hay không. */
    public boolean matchesReceivingAccount(BankTransfer transfer) {
        return bankBin.equals(transfer.receivingBankBin())
                && bankAccountNumber.equals(transfer.receivingAccountNumber());
    }

    /** @return true nếu lần gọi này thực sự đổi trạng thái */
    public boolean confirm(Instant now) {
        if (status == IntentStatus.CONFIRMED) {
            return false;
        }
        status = IntentStatus.CONFIRMED;
        confirmedAt = now;
        return true;
    }

    public boolean close(IntentStatus reason) {
        if (status != IntentStatus.PENDING) {
            return false;
        }
        status = reason;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public long amountVnd() {
        return amountVnd;
    }

    public PaymentReference reference() {
        return reference;
    }

    public UUID bankAccountId() {
        return bankAccountId;
    }

    public String bankBin() {
        return bankBin;
    }

    public String bankAccountNumber() {
        return bankAccountNumber;
    }

    public String vietQrPayload() {
        return vietQrPayload;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public IntentStatus status() {
        return status;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }
}
