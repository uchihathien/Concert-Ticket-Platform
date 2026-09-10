// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

/** Vòng đời một yêu cầu thanh toán. CONFIRMED là trạng thái cuối — tiền đã vào thì không lùi. */
public enum PaymentStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
