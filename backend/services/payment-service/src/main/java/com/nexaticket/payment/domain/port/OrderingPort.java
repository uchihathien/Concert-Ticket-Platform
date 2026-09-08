// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import java.util.UUID;

/**
 * Báo cho ordering-service rằng tiền đã về.
 *
 * <p>Gọi HTTP đồng bộ chứ không qua message: SePay chờ mã trả về của ta để quyết định có retry
 * hay không, nên ta phải biết chắc Ordering đã nhận trước khi trả 2xx. Bắn một message rồi trả
 * 2xx ngay sẽ khiến một lần RabbitMQ hỏng biến thành "khách đã trả tiền nhưng đơn mãi không PAID"
 * — và SePay thì không retry nữa vì đã nhận 2xx.
 */
public interface OrderingPort {

    /** Phải idempotent ở phía Ordering. */
    void confirmPayment(UUID orderId);

    class OrderingUnavailableException extends RuntimeException {
        public OrderingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
