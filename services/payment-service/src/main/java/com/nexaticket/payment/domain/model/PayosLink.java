// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

/**
 * Link thanh toán payOS đã tạo — thứ thay cho mã VietQR nền tảng tự sinh trước đây (ADR-0016).
 *
 * <p>Toàn bộ nội dung ở đây là <b>của payOS</b>, ta chỉ lưu lại. Và phải lưu lại chứ không hỏi lại:
 * tài khoản ảo gắn với một link là cố định, còn cấu hình kênh thanh toán thì đổi được. Một đơn mở
 * hôm nay vẫn phải quét ra đúng tài khoản ảo của hôm nay, kể cả sau khi kênh đã đổi.
 *
 * @param orderCode mã số nguyên payOS dùng để định danh link. <b>Khoá đối soát duy nhất</b>: webhook
 *     trả đúng số này, nên nó đứng thay cho việc rút mã đơn khỏi nội dung chuyển khoản như thời
 *     SePay — không còn phải đoán giữa chuỗi chữ mà ngân hàng thêm vào.
 * @param paymentLinkId định danh chuỗi của link phía payOS, dùng khi tra cứu và khi webhook không
 *     có mã giao dịch ngân hàng
 * @param checkoutUrl trang thanh toán payOS host. Khách mở trang này thì chọn được ngân hàng, thấy
 *     trạng thái trả tiền theo thời gian thực, và không phải tự gõ nội dung chuyển khoản.
 * @param qrCode chuỗi EMVCo của mã VietQR payOS sinh, trỏ về tài khoản ảo của link này. Frontend tự
 *     render thành ảnh — backend cố ý không trả ảnh.
 * @param bin mã ngân hàng của tài khoản ảo
 * @param accountNumber số tài khoản ảo nhận tiền cho <i>riêng</i> link này
 * @param accountName tên chủ tài khoản ảo, để khách đối chiếu trước khi bấm chuyển
 * @param amountVnd số tiền payOS ghi nhận cho link. Lưu lại để đối chiếu với số tiền của đơn.
 * @param status trạng thái payOS trả về lúc tạo — luôn là {@code PENDING} ở đường thường
 */
public record PayosLink(
        long orderCode,
        String paymentLinkId,
        String checkoutUrl,
        String qrCode,
        String bin,
        String accountNumber,
        String accountName,
        long amountVnd,
        String status) {

    public PayosLink {
        require(orderCode > 0, "orderCode phải dương: " + orderCode);
        require(notBlank(paymentLinkId), "payOS không trả paymentLinkId");
        require(notBlank(checkoutUrl), "payOS không trả checkoutUrl");
        // Không có qrCode thì khách không quét được gì, và một intent đã ghi vào database với cột
        // vietqr_payload rỗng là một đơn vĩnh viễn không trả được tiền. Chặn ngay tại đây.
        require(notBlank(qrCode), "payOS không trả qrCode");
        require(notBlank(bin), "payOS không trả bin");
        require(notBlank(accountNumber), "payOS không trả accountNumber");
        require(amountVnd > 0, "Số tiền payOS trả về phải dương: " + amountVnd);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
