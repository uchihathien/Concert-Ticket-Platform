// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import java.util.UUID;

/**
 * Báo sang ordering-service rằng tiền đã vào.
 *
 * <p>Đồng bộ chứ không qua message, và đó là lựa chọn có chủ đích: bên kia là một endpoint
 * idempotent, còn ở đây ta đang đứng trong một webhook mà ngân hàng sẽ giao lại nếu ta không trả
 * 2xx. Nghĩa là ta đã có sẵn một cơ chế retry tốt hơn outbox — của chính nhà cung cấp.
 */
public interface OrderingPort {

    /**
     * @return Ordering đã làm gì với khoản tiền này
     * @throws OrderingUnavailableException khi không gọi được; người gọi phải trả 5xx để được giao
     *     lại
     */
    Confirmation confirmPayment(UUID orderId);

    /**
     * Câu trả lời của Ordering.
     *
     * <p>Cả ba đều là câu trả lời <b>cuối cùng</b> — không giá trị nào nghĩa là "thử lại". Lỗi tạm
     * thời đi bằng {@link OrderingUnavailableException}, và đó là lúc duy nhất việc giao lại có
     * ích.
     */
    enum Confirmation {
        /** Đơn vừa chuyển sang PAID; vé sẽ được phát. */
        PAID,
        /** Đơn đã PAID từ trước — webhook trùng, chuyện thường ngày. */
        ALREADY_PAID,
        /**
         * Tiền vào một đơn đã đóng.
         *
         * <p>Đơn sang MANUAL_REVIEW và <b>không</b> có vé nào được phát: ghế đã nhả lúc đóng đơn
         * và có thể đã bán cho người khác. Khoản tiền này cần một con người xử lý.
         */
        MANUAL_REVIEW
    }

    class OrderingUnavailableException extends RuntimeException {
        public OrderingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
