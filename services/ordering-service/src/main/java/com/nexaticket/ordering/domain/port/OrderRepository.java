// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import com.nexaticket.ordering.domain.model.Order;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Trạng thái của nhiều đơn trong một lần đi database.
     *
     * <p>Chỉ trả trạng thái, không trả cả aggregate: người gọi (ticketing, lúc dựng danh sách vé
     * cho ban tổ chức) cần đúng một cột cho tới 50 đơn, và dựng 50 {@link Order} kèm dòng đơn là
     * đọc vài trăm dòng để dùng một chữ.
     *
     * <p>Đơn không tồn tại thì vắng mặt trong map, không có giá trị rỗng: "không có đơn này" khác
     * "đơn này không có trạng thái", và người gọi phải phân biệt được.
     */
    Map<UUID, String> statusesOf(Collection<UUID> orderIds);

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
