// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import java.time.Instant;
import java.util.UUID;

/** Gọi payment-service để mở yêu cầu thanh toán và lấy mã VietQR. */
public interface PaymentPort {

    /**
     * Mở payment intent. Idempotent theo {@code orderId}.
     *
     * @param totalVnd số tiền khách phải chuyển
     * @throws RemoteCallException khi payment-service không phản hồi trong hạn
     */
    Intent openIntent(UUID orderId, UUID organizationId, long totalVnd, Instant expiresAt);

    /** Bù trừ: huỷ intent. Phải idempotent. */
    void cancelIntent(UUID orderId);

    /**
     * @param paymentReference nội dung chuyển khoản khách phải ghi, ví dụ {@code NTK7M2QP9X}
     * @param vietQrPayload chuỗi EMVCo; frontend tự render thành QR — nhẹ hơn trả ảnh và không
     *     phụ thuộc dịch vụ ngoài
     */
    record Intent(String paymentReference, String vietQrPayload, String bankBin, String bankAccountNumber) {}
}
