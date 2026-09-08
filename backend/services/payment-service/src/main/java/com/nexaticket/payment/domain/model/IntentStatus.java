// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

/** Vòng đời một yêu cầu thanh toán. */
public enum IntentStatus {
    PENDING,
    CONFIRMED,

    /** Saga checkout bù trừ, hoặc khách huỷ đơn. */
    CANCELLED,

    /** Quá hạn chuyển khoản. Tiền đến sau mốc này phải qua tay người. */
    EXPIRED;

    public boolean isOpen() {
        return this == PENDING;
    }
}
