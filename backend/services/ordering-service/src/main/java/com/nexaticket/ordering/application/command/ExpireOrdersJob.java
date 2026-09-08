// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đóng các đơn quá hạn chuyển khoản — lưới an toàn thứ ba của saga.
 *
 * <p>Điều kiện {@code status = 'AWAITING_PAYMENT'} nằm trong chính câu SQL nhận việc, không phải
 * kiểm ở Java sau khi đọc. Giữa lúc worker đọc và lúc worker ghi, webhook có thể vừa chuyển đơn
 * sang PAID; đóng một đơn đã nhận tiền nghĩa là khách mất tiền và mất vé cùng lúc.
 */
@Service
public class ExpireOrdersJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireOrdersJob.class);
    private static final int BATCH_SIZE = 200;

    private final OrderRepository orders;
    private final OutboxPort outbox;
    private final Clock clock;

    public ExpireOrdersJob(OrderRepository orders, OutboxPort outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @return số đơn đã đóng — trả về để test khẳng định được, thay vì phải chờ và đoán
     */
    @Transactional
    public int runOnce() {
        Instant now = clock.instant();
        List<Order> expired = orders.claimExpired(now, BATCH_SIZE);
        for (Order order : expired) {
            if (order.close(OrderStatus.EXPIRED, now, "Quá hạn chuyển khoản")) {
                orders.updateStatus(order);
                outbox.orderClosed(order);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Đã đóng {} đơn quá hạn thanh toán", expired.size());
        }
        return expired.size();
    }
}
