// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Khách tự huỷ đơn chưa thanh toán.
 *
 * <h2>Đóng đơn thì phải đóng cả link thanh toán</h2>
 *
 * <p>Đây là thứ bản trước thiếu, và nó là lỗ hổng mất tiền thật: mỗi link payOS có một tài khoản
 * ảo sống riêng tới {@code expiredAt} — bằng đúng hạn thanh toán của đơn. Khách huỷ ở phút thứ ba
 * rồi mở lại tab cũ và chuyển khoản: tiền vào thật, trong khi đơn đã huỷ và ghế đã bán cho người
 * khác. Đường sửa duy nhất sau đó là hoàn tiền bằng tay.
 *
 * <p>Nên thứ tự ở đây là: ghi trạng thái (một transaction ngắn), rồi <b>đóng link trước, nhả ghế
 * sau</b>. Link còn sống là thứ tạo ra tiền mồ côi; ghế chưa nhả chỉ là một chỗ chưa bán lại được.
 *
 * <h2>Vì sao không còn {@code @Transactional}</h2>
 *
 * <p>Cùng lý do với {@link PlaceOrderHandler}: phương thức này gọi hai service khác, và giữ một
 * kết nối database suốt thời gian đó là cách nhanh nhất làm cạn pool. Phần ghi nằm gọn trong
 * {@link OrderTransactions#closeByUser}, phần gọi mạng nằm ngoài.
 */
@Service
public class CloseOrderHandler {

    private static final Logger log = LoggerFactory.getLogger(CloseOrderHandler.class);

    private final OrderTransactions tx;
    private final PaymentPort payments;
    private final InventoryPort inventory;
    private final Clock clock;

    public CloseOrderHandler(OrderTransactions tx, PaymentPort payments, InventoryPort inventory, Clock clock) {
        this.tx = tx;
        this.payments = payments;
        this.inventory = inventory;
        this.clock = clock;
    }

    public void cancel(UUID orderId, UUID userId) {
        Order order = tx.closeByUser(orderId, userId, clock.instant());
        closePaymentBestEffort(order.id());
        releaseSeatsBestEffort(order.id());
    }

    /**
     * Đóng link payOS — "cố gắng hết sức", nhưng là bước quan trọng nhất trong hai bước.
     *
     * <p>Hỏng ở đây <b>không</b> được làm rollback việc huỷ đơn: đơn đã huỷ vẫn phải là đã huỷ, và
     * bắt khách bấm lại vì payment-service đang chậm là đổi một lỗi nhỏ lấy một lỗi to hơn. Link
     * không đóng được vẫn tự hết hạn theo {@code expiredAt}, nên cửa sổ rủi ro là hữu hạn — nhưng
     * nó có thật, nên log ở mức WARN kèm {@code orderId}.
     */
    private void closePaymentBestEffort(UUID orderId) {
        try {
            payments.cancelIntent(orderId);
        } catch (RuntimeException e) {
            log.warn("Không đóng được link thanh toán của đơn {} — nó sẽ tự hết hạn", orderId, e);
        }
    }

    /**
     * Nhả chỗ ngay thay vì chỉ dựa vào sự kiện: khách vừa huỷ thường là để chọn chỗ khác, và bắt
     * họ đợi outbox publisher chạy xong là mất một lần bán.
     *
     * <p>Hỏng thì consumer của {@code order.cancelled} bên Inventory dọn nốt.
     */
    private void releaseSeatsBestEffort(UUID orderId) {
        try {
            inventory.cancelReservation(orderId);
        } catch (RuntimeException e) {
            log.warn("Không nhả được chỗ ngay cho đơn {}, chờ sự kiện order.cancelled", orderId, e);
        }
    }

    /** Lý do đóng đơn khi khách tự bấm huỷ. Tách ra để {@link OrderTransactions} dùng chung. */
    static final class Reason {
        static final OrderStatus STATUS = OrderStatus.CANCELLED;
        static final String NOTE = "Khách huỷ";

        private Reason() {}
    }

    /** Đơn không tồn tại, hoặc của người khác — cả hai đều là 404. */
    static ApiException notFound() {
        return new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found");
    }
}
