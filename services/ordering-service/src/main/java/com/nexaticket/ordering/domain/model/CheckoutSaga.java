// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

import java.util.UUID;

/**
 * Nhật ký các bước saga đã thực sự thay đổi trạng thái ở service khác.
 *
 * <p>Chỉ ghi lại những bước <b>có gì để bù trừ</b>. Bước tra giá ở Catalog không đổi gì nên không
 * có mặt ở đây; bước đặt chỗ ở Inventory thì có.
 *
 * <p>Cách dùng bắt buộc: <b>ghi cờ trước khi gọi, không phải sau</b>. Nếu ghi sau, một lần process
 * chết ngay giữa "inventory đã đặt chỗ xong" và "ordering ghi cờ" sẽ để lại ghế RESERVED mà không
 * dấu vết nào — đúng trường hợp bảng này sinh ra để chống.
 */
public final class CheckoutSaga {

    private final UUID orderId;
    private final UUID holdId;
    private final UUID userId;

    private SagaStatus status;
    private boolean seatsReserved;
    private boolean paymentIntentOpen;
    private int attempts;
    private String lastError;

    private CheckoutSaga(UUID orderId, UUID holdId, UUID userId, SagaStatus status) {
        this.orderId = orderId;
        this.holdId = holdId;
        this.userId = userId;
        this.status = status;
    }

    public static CheckoutSaga start(UUID orderId, UUID holdId, UUID userId) {
        return new CheckoutSaga(orderId, holdId, userId, SagaStatus.STARTED);
    }

    public static CheckoutSaga rehydrate(
            UUID orderId,
            UUID holdId,
            UUID userId,
            SagaStatus status,
            boolean seatsReserved,
            boolean paymentIntentOpen,
            int attempts) {
        CheckoutSaga saga = new CheckoutSaga(orderId, holdId, userId, status);
        saga.seatsReserved = seatsReserved;
        saga.paymentIntentOpen = paymentIntentOpen;
        saga.attempts = attempts;
        return saga;
    }

    public void seatsReserved() {
        this.seatsReserved = true;
    }

    public void paymentIntentOpened() {
        this.paymentIntentOpen = true;
    }

    public void completed() {
        this.status = SagaStatus.COMPLETED;
    }

    /** Không có gì thay đổi ở service khác ⇒ không cần bù trừ. */
    public void failedBeforeAnyEffect(String error) {
        this.status = SagaStatus.FAILED;
        this.lastError = truncate(error);
    }

    public void compensated() {
        this.status = SagaStatus.COMPENSATED;
        this.seatsReserved = false;
        this.paymentIntentOpen = false;
    }

    public void compensationFailed(String error) {
        this.status = SagaStatus.COMPENSATION_PENDING;
        this.attempts++;
        this.lastError = truncate(error);
    }

    public boolean hasSomethingToCompensate() {
        return seatsReserved || paymentIntentOpen;
    }

    /** Thông điệp lỗi vào cột TEXT và vào log; stack trace dài không giúp gì ở đây. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID holdId() {
        return holdId;
    }

    public UUID userId() {
        return userId;
    }

    public SagaStatus status() {
        return status;
    }

    public boolean isSeatsReserved() {
        return seatsReserved;
    }

    public boolean isPaymentIntentOpen() {
        return paymentIntentOpen;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }
}
