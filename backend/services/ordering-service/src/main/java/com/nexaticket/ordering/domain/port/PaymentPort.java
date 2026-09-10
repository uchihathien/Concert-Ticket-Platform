// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import java.time.Instant;
import java.util.UUID;

/** Gọi payment-service để mở link thanh toán payOS và lấy mã VietQR của nó (ADR-0016). */
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
     * Kết quả mở link thanh toán.
     *
     * <p>Ordering <b>lưu lại toàn bộ</b> chứ không hỏi lại payment-service mỗi lần đọc đơn: mã khách đã
     * nhìn thấy không được đổi, và mở lại trang đơn hàng không nên phụ thuộc payment-service còn sống.
     *
     * @param paymentReference nội dung chuyển khoản, ví dụ {@code NT1000001}. Từ ADR-0016 nó không còn là
     *     khoá đối soát — payOS khớp tiền với đơn bằng {@code orderCode} trong một webhook đã ký — nên
     *     khách gõ sai nội dung không còn làm tiền mồ côi. Giữ lại để người đọc đối chiếu.
     * @param vietQrPayload chuỗi EMVCo payOS sinh, trỏ về tài khoản ảo của riêng link này; frontend tự
     *     render thành QR — nhẹ hơn trả ảnh và không phụ thuộc dịch vụ sinh ảnh nào
     * @param checkoutUrl trang thanh toán payOS host. Đây là <b>đường chính</b> cho khách: ở đó họ chọn
     *     ngân hàng và thấy trạng thái trả tiền theo thời gian thực. QR là đường phụ cho người muốn tự
     *     quét bằng app ngân hàng.
     * @param bankAccountName tên chủ tài khoản ảo. Cần hiển thị vì mỗi link một tài khoản khác nhau, và
     *     một số tài khoản lạ không kèm tên là thứ làm người ta do dự đúng ở bước cuối.
     */
    record Intent(
            String paymentReference,
            String vietQrPayload,
            String checkoutUrl,
            String bankBin,
            String bankAccountNumber,
            String bankAccountName) {}
}
