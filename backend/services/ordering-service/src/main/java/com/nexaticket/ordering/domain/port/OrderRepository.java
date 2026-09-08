// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import com.nexaticket.ordering.domain.model.Order;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    /**
     * @throws DuplicateHoldException khi lần giữ chỗ này đã có đơn — chốt chặn cuối cùng chống
     *     việc một lần giữ chỗ sinh hai đơn
     */
    void save(Order order);

    Optional<Order> findById(UUID orderId);

    /** Đường idempotent của saga: cùng một lần giữ chỗ chỉ có đúng một đơn. */
    Optional<Order> findByHoldId(UUID holdId);

    Optional<Order> findByPaymentReference(String paymentReference);

    List<Order> findByUser(UUID userId, int limit, int offset);

    void updateStatus(Order order);

    void attachPaymentReference(UUID orderId, String paymentReference);

    /**
     * Nhận các đơn đã quá hạn thanh toán để worker đóng.
     *
     * <p>Điều kiện {@code status = 'AWAITING_PAYMENT'} nằm trong chính câu lệnh, không phải kiểm
     * ở Java: giữa lúc worker đọc và lúc worker ghi, webhook có thể vừa chuyển đơn sang PAID —
     * và đóng một đơn đã nhận tiền là mất tiền của khách.
     */
    List<Order> claimExpired(Instant now, int batchSize);

    class DuplicateHoldException extends RuntimeException {
        public DuplicateHoldException(Throwable cause) {
            super("Lần giữ chỗ này đã có đơn hàng", cause);
        }
    }
}
