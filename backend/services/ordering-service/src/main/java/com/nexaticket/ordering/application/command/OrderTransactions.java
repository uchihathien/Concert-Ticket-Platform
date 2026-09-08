// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.port.CheckoutSagaRepository;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Các transaction ngắn mà saga cần.
 *
 * <p>Tồn tại như một bean riêng vì {@code @Transactional} của Spring hoạt động qua proxy: gọi một
 * phương thức {@code @Transactional} từ bên trong cùng một bean sẽ <b>không</b> mở transaction nào.
 * {@link PlaceOrderHandler} cố ý không có transaction bao ngoài, nên mỗi lần ghi phải đi qua đây.
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
