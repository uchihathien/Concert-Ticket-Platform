// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.query;

import com.nexaticket.payment.domain.model.PaymentIntent;

/**
 * Yêu cầu thanh toán như saga checkout nhìn thấy.
 *
 * <p>Cố ý không có {@code amountVnd} hay {@code orderId}: Ordering đã biết cả hai, và trả lại
 * chúng chỉ tạo thêm một nguồn sự thật thứ hai để lệch nhau.
 *
 * @param vietQrPayload chuỗi EMVCo; frontend tự render QR
 */
public record PaymentIntentView(
        String paymentReference, String vietQrPayload, String bankBin, String bankAccountNumber) {

    public static PaymentIntentView from(PaymentIntent intent) {
        return new PaymentIntentView(
                intent.reference().value(), intent.vietQrPayload(), intent.bankBin(), intent.bankAccountNumber());
    }
}
