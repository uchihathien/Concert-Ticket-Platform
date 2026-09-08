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

    class OrderingUnavailableException extends RuntimeException {
        public OrderingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
