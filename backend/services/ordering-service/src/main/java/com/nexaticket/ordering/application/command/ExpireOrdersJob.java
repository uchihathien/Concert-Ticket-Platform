// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Đóng các đơn quá hạn chuyển khoản, và nhả chỗ của chúng — lưới an toàn thứ ba của saga.
 *
 * <p>Điều kiện {@code status = 'AWAITING_PAYMENT'} nằm trong chính câu SQL nhận việc, không phải
 * kiểm ở Java sau khi đọc. Giữa lúc worker đọc và lúc worker ghi, webhook có thể vừa chuyển đơn
 * sang PAID; đóng một đơn đã nhận tiền nghĩa là khách mất tiền và mất vé cùng lúc.
 *
 * <h2>Vì sao phải tự nhả chỗ, không chỉ bắn sự kiện</h2>
 *
 * <p>Đây là đường đi <b>phổ biến nhất</b> của một lần checkout hỏng: khách tắt trình duyệt và
 * không bao giờ quay lại. Lúc đó lần giữ chỗ đã ở trạng thái {@code CONVERTED} nên
 * {@code ExpireHoldsJob} bên Inventory bỏ qua nó — nó chỉ nhận hold còn {@code ACTIVE}. Nếu ở đây
 * chỉ đóng đơn rồi bắn {@code order.expired}, chỗ sẽ nằm mãi ở {@code RESERVED}: không bán lại
 * được, và vì {@code holder_user_id} không được xoá, chính người khách đó bị tính vào trần
 * {@code max_tickets_per_customer} vĩnh viễn cho suất diễn đó (ADR-1014 §2).
 *
 * <p>Lời gọi nhả chỗ là <b>cố gắng hết sức</b>, giống {@link CloseOrderHandler}: hỏng thì log và đi
 * tiếp, không rollback việc đóng đơn. Đường chắc chắn là consumer của {@code order.expired} bên
 * Inventory; lời gọi ở đây chỉ để nhả chỗ ngay thay vì chờ một vòng message.
 *
 * <h2>Vì sao không có {@code @Transactional}</h2>
 *
 * <p>Cùng lý do với {@link PlaceOrderHandler}: phương thức này gọi mạng, và giữ một kết nối
 * database trong lúc chờ service khác là cách nhanh nhất làm cạn pool. Phần ghi nằm gọn trong
 * {@link OrderTransactions#closeExpired}, phần gọi mạng nằm ngoài.
 */
@Service
public class ExpireOrdersJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireOrdersJob.class);
    private static final int BATCH_SIZE = 200;

    private final OrderTransactions tx;
    private final PaymentPort payments;
    private final InventoryPort inventory;
    private final Clock clock;

    public ExpireOrdersJob(OrderTransactions tx, PaymentPort payments, InventoryPort inventory, Clock clock) {
        this.tx = tx;
        this.payments = payments;
        this.inventory = inventory;
        this.clock = clock;
    }

    /**
     * @return số đơn đã đóng — trả về để test khẳng định được, thay vì phải chờ và đoán
     */
    public int runOnce() {
        List<UUID> closed = tx.closeExpired(clock.instant(), BATCH_SIZE);
        for (UUID orderId : closed) {
            // Đóng link TRƯỚC khi nhả ghế. Sau khi ghế được nhả, một khoản tiền vào link cũ là
            // tiền vào một đơn không còn chỗ — trường hợp duy nhất phải hoàn bằng tay.
            closePaymentBestEffort(orderId);
            releaseSeatsBestEffort(orderId);
        }
        if (!closed.isEmpty()) {
            log.info("Đã đóng {} đơn quá hạn thanh toán và nhả chỗ của chúng", closed.size());
        }
        return closed.size();
    }

    /**
     * Hỏng ở đây <b>không</b> được làm rollback việc đóng đơn: đơn đã hết hạn vẫn phải là đã hết
     * hạn. Log ở mức WARN kèm {@code orderId} để lần ra được — một chỗ kẹt ở {@code RESERVED} là
     * mất doanh thu im lặng, và dòng log này là dấu vết duy nhất trước khi consumer dọn nốt.
     */
    private void closePaymentBestEffort(UUID orderId) {
        try {
            payments.cancelIntent(orderId);
        } catch (RuntimeException e) {
            log.warn("Không đóng được link thanh toán của đơn quá hạn {} — nó sẽ tự hết hạn", orderId, e);
        }
    }

    private void releaseSeatsBestEffort(UUID orderId) {
        try {
            inventory.cancelReservation(orderId);
        } catch (RuntimeException e) {
            log.warn("Không nhả được chỗ ngay cho đơn quá hạn {}, chờ sự kiện order.expired", orderId, e);
        }
    }
}
