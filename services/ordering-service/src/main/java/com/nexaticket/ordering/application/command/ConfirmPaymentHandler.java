// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đánh dấu đơn đã nhận tiền, sau khi payment-service xác nhận webhook.
 *
 * <p>Idempotent là bắt buộc, không phải tuỳ chọn: payOS retry webhook cho tới khi nhận 2xx, nên
 * lời gọi này gần như chắc chắn sẽ đến hai lần trong đời hệ thống. Lần thứ hai không được đổi
 * {@code paidAt} — mốc đó đi vào sổ cái và vào hạn giữ tiền — và không được bắn {@code OrderPaid}
 * lần nữa, vì ticketing-service sẽ phát vé đôi.
 *
 * <h2>Đơn đã đóng mà tiền vẫn vào</h2>
 *
 * <p>Nhánh này <b>không phải lỗi</b> và phải trả lời dứt khoát. Job hết hạn chạy mỗi 15 giây còn
 * link payOS sống tới đúng hạn thanh toán, nên một lần chuyển khoản ở phút chót được xác nhận sau
 * khi đơn vừa đóng là chuyện sẽ xảy ra.
 *
 * <p>Bản trước ném {@code IllegalStateException} ở đây, và cái giá của nó rất cụ thể: 500 trả về
 * payment-service → transaction bên đó rollback → <b>mất luôn dòng {@code bank_webhook_log} vừa
 * ghi</b> → payOS giao lại mãi một message không bao giờ xử lý được. Khách mất tiền, không có vé,
 * và không còn dấu vết nào để đối soát.
 *
 * <p>Giờ nó chuyển đơn sang {@link OrderStatus#MANUAL_REVIEW} và trả về {@link Outcome} tương ứng.
 * Không phát vé — ghế đã nhả lúc đóng đơn và có thể đã bán cho người khác.
 */
@Service
public class ConfirmPaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPaymentHandler.class);

    private final OrderRepository orders;
    private final OutboxPort outbox;
    private final Clock clock;

    public ConfirmPaymentHandler(OrderRepository orders, OutboxPort outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Kết quả của một lần xác nhận, để payment-service biết phải ghi nhật ký gì.
     *
     * <p>Không có giá trị nào ở đây nghĩa là "thử lại": cả ba đều là câu trả lời cuối cùng. Lỗi
     * tạm thời vẫn đi bằng exception và vẫn thành 5xx — đó là lúc duy nhất việc giao lại có ích.
     */
    public enum Outcome {
        /** Lần gọi này chuyển đơn sang PAID và bắn {@code order.paid}. */
        PAID,
        /** Đơn đã PAID từ trước; không đổi gì. */
        ALREADY_PAID,
        /** Tiền vào một đơn đã đóng. Đơn sang MANUAL_REVIEW, không phát vé, cần người xử lý. */
        MANUAL_REVIEW
    }

    @Transactional
    public Outcome handle(UUID orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(OrderingErrorCode.ORDER_NOT_FOUND, "Order not found"));

        if (order.status() == OrderStatus.PAID) {
            log.info("Đơn {} đã PAID từ trước, bỏ qua xác nhận trùng", orderId);
            return Outcome.ALREADY_PAID;
        }

        if (order.status() != OrderStatus.AWAITING_PAYMENT) {
            return flagForReview(order);
        }

        order.markPaid(clock.instant());
        orders.updateStatus(order);
        outbox.orderPaid(order);
        return Outcome.PAID;
    }

    /**
     * Tiền thật vào một đơn đã đóng.
     *
     * <p>Log ở mức ERROR chứ không WARN: đây là một khoản tiền có thật nằm trong tài khoản ta mà
     * khách chưa nhận được gì, và nó chỉ được giải quyết khi một con người nhìn thấy.
     */
    private Outcome flagForReview(Order order) {
        // Đọc trạng thái cũ TRƯỚC khi đổi: sau lời gọi dưới đây nó đã là MANUAL_REVIEW, và một
        // dòng log nói "nhận tiền khi đang ở trạng thái MANUAL_REVIEW" thì không nói được gì.
        OrderStatus previous = order.status();
        if (order.flagManualReview(clock.instant(), "Tiền vào sau khi đơn đã " + previous)) {
            orders.updateStatus(order);
            log.error(
                    "ĐỐI SOÁT TAY: đơn {} ({}) nhận tiền khi đang ở trạng thái {} — ghế đã nhả, không phát vé",
                    order.id(),
                    order.orderNumber().value(),
                    previous);
        } else {
            log.warn("Đơn {} đã ở MANUAL_REVIEW, bỏ qua xác nhận trùng", order.id());
        }
        return Outcome.MANUAL_REVIEW;
    }
}
