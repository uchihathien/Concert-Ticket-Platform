// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.CheckoutSagaRepository;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Các transaction ngắn mà saga cần.
 *
 * <p>Tồn tại như một bean riêng vì {@code @Transactional} của Spring hoạt động qua proxy: gọi một
 * phương thức {@code @Transactional} từ bên trong cùng một bean sẽ <b>không</b> mở transaction nào.
 * {@link PlaceOrderHandler} và {@link ExpireOrdersJob} cố ý không có transaction bao ngoài, nên mỗi
 * lần ghi của chúng phải đi qua đây.
 *
 * <p>Mỗi phương thức bao đúng một lần ghi và không chứa lời gọi mạng nào — đó là điều kiện để pool
 * kết nối không bị giữ trong lúc chờ service khác.
 */
@Service
public class OrderTransactions {

    private final OrderRepository orders;
    private final CheckoutSagaRepository sagas;
    private final OutboxPort outbox;

    public OrderTransactions(OrderRepository orders, CheckoutSagaRepository sagas, OutboxPort outbox) {
        this.orders = orders;
        this.sagas = sagas;
        this.outbox = outbox;
    }

    @Transactional
    public void saveSaga(CheckoutSaga saga) {
        sagas.save(saga);
    }

    @Transactional
    public void updateSaga(CheckoutSaga saga) {
        sagas.update(saga);
    }

    /**
     * Khách tự huỷ đơn: kiểm chủ sở hữu, đóng đơn, ghi sự kiện — <b>một transaction</b>.
     *
     * <p>Nằm ở đây chứ không ở {@link CloseOrderHandler} vì handler còn phải gọi payment-service và
     * inventory-service sau khi commit, mà lời gọi mạng thì không được nằm trong transaction.
     *
     * @return đơn vừa đóng, để người gọi biết cần dọn dẹp những gì
     * @throws com.nexaticket.platform.web.error.ApiException 404 nếu đơn không tồn tại hoặc của
     *     người khác, 409 nếu đơn không còn chờ thanh toán
     */
    @Transactional
    public Order closeByUser(UUID orderId, UUID userId, Instant now) {
        Order order = orders.findById(orderId).orElseThrow(CloseOrderHandler::notFound);

        // Đơn của người khác cũng trả 404: trả 403 là xác nhận orderId đó có thật.
        if (!order.isOwnedBy(userId)) {
            throw CloseOrderHandler.notFound();
        }
        if (!order.close(CloseOrderHandler.Reason.STATUS, now, CloseOrderHandler.Reason.NOTE)) {
            throw new ApiException(
                    OrderingErrorCode.ORDER_NOT_OPEN, "Order is no longer awaiting payment: " + order.status());
        }
        orders.updateStatus(order);
        outbox.orderClosed(order);
        return order;
    }

    /**
     * Đóng một lô đơn quá hạn chuyển khoản.
     *
     * <p>Trả về id của những đơn <b>thực sự vừa bị đóng ở lần gọi này</b>, không phải cả lô nhận
     * được: giữa lúc worker đọc và lúc worker ghi, webhook có thể vừa chuyển một đơn sang PAID, và
     * {@link Order#close} từ chối đóng nó. Người gọi dùng danh sách này để nhả chỗ, nên một id thừa
     * ở đây là nhả chỗ của một đơn đã trả tiền.
     *
     * <p>Nằm ở đây chứ không ở {@link ExpireOrdersJob} vì job còn phải gọi Inventory sau khi
     * commit, mà lời gọi mạng thì không được nằm trong transaction.
     */
    @Transactional
    public List<UUID> closeExpired(Instant now, int batchSize) {
        List<UUID> closed = new ArrayList<>();
        for (Order order : orders.claimExpired(now, batchSize)) {
            if (order.close(OrderStatus.EXPIRED, now, "Quá hạn chuyển khoản")) {
                orders.updateStatus(order);
                outbox.orderClosed(order);
                closed.add(order.id());
            }
        }
        return closed;
    }

    /**
     * Ghi đơn, các dòng, sự kiện outbox và trạng thái saga — <b>một transaction</b>.
     *
     * <p>Outbox phải cùng transaction với đơn (ADR-0005): nếu publish trước khi commit thì có thể
     * bắn {@code OrderCreated} cho một đơn đã rollback, và ticketing-service sẽ phát vé cho đơn
     * không tồn tại.
     */
    @Transactional
    public void persistOrder(Order order, CheckoutSaga saga) {
        orders.save(order);
        saga.completed();
        sagas.update(saga);
        outbox.orderCreated(order);
    }
}
