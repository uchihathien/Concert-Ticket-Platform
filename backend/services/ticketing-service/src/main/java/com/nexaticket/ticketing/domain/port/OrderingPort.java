// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Đọc chi tiết đơn hàng từ ordering-service.
 *
 * <p>Vì sao phải gọi thêm một lần thay vì đọc thẳng từ message: sự kiện {@code order.paid} cố ý
 * <b>không</b> mang danh sách chỗ. Nhét cả đơn hàng vào message sẽ biến mọi thay đổi cấu trúc đơn
 * thành một đợt phá vỡ hợp đồng với mọi consumer, trong khi số consumer cần chi tiết chỉ là một.
 */
public interface OrderingPort {

    /**
     * @throws OrderingUnavailableException khi không đọc được — consumer sẽ để message giao lại
     */
    PaidOrder fetch(UUID orderId);

    record PaidOrder(
            UUID orderId, UUID eventSessionId, UUID organizationId, UUID userId, String status, List<Line> items) {

        /** Đơn chưa PAID thì chưa được phát vé — bảo vệ trước một sự kiện đến sớm hoặc sai. */
        public boolean isPaid() {
            return "PAID".equals(status);
        }
    }

    record Line(
            UUID orderItemId,
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            String ticketTypeName) {}

    /**
     * Ordering tạm thời không trả lời được — mạng, timeout, 5xx.
     *
     * <p>Consumer để message được giao lại: lát nữa hỏi lại là xong.
     */
    class OrderingUnavailableException extends RuntimeException {
        public OrderingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Ordering trả lời dứt khoát rằng đơn này không tồn tại.
     *
     * <p>Tách khỏi {@link OrderingUnavailableException} là bắt buộc, không phải cho gọn. Trước đây
     * adapter gói MỌI lỗi thành "không đọc được", nên một cái 404 bị xử lý y hệt "Ordering đang
     * sập": message được giao lại mãi mãi, và vì hàng đợi chỉ có một consumer nên nó <b>chặn đầu
     * hàng</b> — mọi đơn phía sau không bao giờ được phát vé.
     *
     * <p>Đã xảy ra thật: bảy message trỏ vào những đơn không còn tồn tại làm kẹt cả hàng đợi, và
     * hai đơn mới mua xong đều không có vé. Nhìn từ phía khách thì "trả tiền rồi mà không có vé",
     * còn log thì chỉ toàn cảnh báo lặp lại của những đơn cũ.
     *
     * <p>404 là câu trả lời dứt khoát: hỏi lại lần thứ một nghìn cũng vẫn 404.
     */
    class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
