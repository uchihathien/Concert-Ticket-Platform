// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderItem;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.PricingPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Saga checkout — đồng bộ, có bù trừ (sagas.md §2).
 *
 * <p>Khách đang nhìn màn hình chờ mã QR, nên không thể trả "đang xử lý, mời quay lại sau". Vì vậy
 * các bước là HTTP đồng bộ + bù trừ, không phải message.
 *
 * <h2>Vì sao phương thức này KHÔNG có {@code @Transactional}</h2>
 *
 * <p>Saga gọi ba service khác, mỗi lời gọi hạn 2 giây. Bọc tất cả trong một transaction sẽ giữ một
 * kết nối database suốt 6 giây xấu nhất; ở 10k đồng thời thì pool 30 kết nối cạn sau 30 request và
 * cả hệ thống đứng — trong khi database gần như không làm gì. Thay vào đó là <b>nhiều transaction
 * ngắn</b>, mỗi cái chỉ bao một lần ghi.
 *
 * <p>Cái giá phải trả là không còn rollback tự động, và đó chính là lý do saga tồn tại: bù trừ
 * bằng tay, có ghi lại bước đã làm.
 *
 * <h2>Ghi cờ TRƯỚC khi gọi</h2>
 *
 * <p>{@code saga.seatsReserved()} được ghi xuống database <b>trước</b> khi gọi Inventory. Ghi sau
 * thì một lần process chết đúng giữa "Inventory đã đặt chỗ" và "Ordering ghi cờ" sẽ để lại ghế
 * RESERVED mà không dấu vết nào để dọn — đúng trường hợp mà bảng {@code checkout_sagas} sinh ra để
 * chống. Ghi trước có thể thừa (đánh dấu một việc chưa từng xảy ra), nhưng bù trừ thừa là vô hại
 * vì mọi bù trừ đều idempotent.
 */
@Service
public class PlaceOrderHandler {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderHandler.class);

    private final OrderRepository orders;
    private final PricingPort pricing;
    private final InventoryPort inventory;
    private final PaymentPort payments;
    private final OrderTransactions tx;
    private final Clock clock;
    private final Duration paymentWindow;

    public PlaceOrderHandler(
            OrderRepository orders,
            PricingPort pricing,
            InventoryPort inventory,
            PaymentPort payments,
            OrderTransactions tx,
            Clock clock,
            OrderingProperties properties) {
        this.orders = orders;
        this.pricing = pricing;
        this.inventory = inventory;
        this.payments = payments;
        this.tx = tx;
        this.clock = clock;
        this.paymentWindow = properties.paymentWindow();
    }

    public record Command(UUID holdId, UUID userId, String promotionCode) {}

    /**
     * @param vietQrPayload chuỗi EMVCo payOS sinh; frontend tự render QR
     * @param checkoutUrl trang thanh toán payOS host — đường chính cho khách, QR là đường phụ
     */
    public record Result(
            UUID orderId,
            String orderNumber,
            long totalVnd,
            String paymentReference,
            String vietQrPayload,
            String checkoutUrl,
            Instant paymentExpiresAt) {}

    public Result handle(Command cmd) {
        // Bấm "Thanh toán" hai lần: trả lại đúng đơn cũ thay vì dựng đơn thứ hai cho cùng
        // một lần giữ chỗ. Idempotency-Key ở filter là lưới thứ nhất, đây là lưới thứ hai,
        // unique index orders.hold_id là lưới thứ ba.
        var existing = orders.findByHoldId(cmd.holdId());
        if (existing.isPresent()) {
            return resultOf(existing.get());
        }

        UUID orderId = UUID.randomUUID();
        CheckoutSaga saga = CheckoutSaga.start(orderId, cmd.holdId(), cmd.userId());
        tx.saveSaga(saga);

        try {
            return runSaga(cmd, orderId, saga);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Saga checkout hỏng ngoài dự kiến cho đơn {}", orderId, e);
            compensate(saga, e.toString());
            throw new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "Checkout failed, please retry");
        }
    }

    private Result runSaga(Command cmd, UUID orderId, CheckoutSaga saga) {
        // ---- Bước 5: đặt chỗ. Bước đầu tiên có gì để bù trừ. ----
        saga.seatsReserved();
        tx.updateSaga(saga);

        InventoryPort.Reservation reservation;
        try {
            reservation = inventory.reserve(orderId, cmd.holdId(), cmd.userId());
        } catch (InventoryPort.SeatsUnavailableException e) {
            // Inventory nói không, dứt khoát. Nó chưa đổi gì nên không có gì để bù trừ.
            saga.failedBeforeAnyEffect(e.getMessage());
            tx.updateSaga(saga);
            throw new ApiException(mapInventoryCode(e.code()), e.getMessage());
        } catch (RemoteCallException e) {
            // KHÔNG biết Inventory đã đặt chỗ hay chưa. Phải giả định là rồi.
            log.warn("Inventory không phản hồi khi đặt chỗ cho đơn {}", orderId, e);
            compensate(saga, e.toString());
            throw new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "Inventory unavailable, please retry");
        }

        // ---- Bước 3: giá và khuyến mãi. Không đổi gì ở Catalog. ----
        long subtotalVnd = reservation.seats().stream()
                .mapToLong(InventoryPort.Seat::priceVnd)
                .sum();
        PricingPort.Pricing quote;
        try {
            quote = pricing.resolve(reservation.eventSessionId(), cmd.promotionCode(), subtotalVnd);
        } catch (PricingPort.PromotionInvalidException e) {
            compensate(saga, e.getMessage());
            throw new ApiException(OrderingErrorCode.PROMOTION_INVALID, e.getMessage());
        } catch (RemoteCallException e) {
            compensate(saga, e.toString());
            throw new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "Pricing unavailable, please retry");
        }

        Instant now = clock.instant();
        Order order = Order.awaitingPayment(
                orderId,
                reservation.eventSessionId(),
                reservation.eventId(),
                reservation.organizationId(),
                cmd.userId(),
                cmd.holdId(),
                buildItems(reservation, quote, subtotalVnd),
                cmd.promotionCode(),
                now,
                paymentWindow);

        // ---- Bước 7: mở payment intent ----
        saga.paymentIntentOpened();
        tx.updateSaga(saga);

        PaymentPort.Intent intent;
        try {
            intent = payments.openIntent(
                    orderId, order.organizationId(), order.total().amountVnd(), order.paymentExpiresAt());
        } catch (RemoteCallException e) {
            log.warn("Payment không phản hồi khi mở intent cho đơn {}", orderId, e);
            compensate(saga, e.toString());
            throw new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "Payment unavailable, please retry");
        }
        order.attachPayment(intent.paymentReference(), intent.vietQrPayload(), intent.checkoutUrl());

        // ---- Bước 8: ghi đơn + outbox, một transaction ----
        try {
            tx.persistOrder(order, saga);
        } catch (OrderRepository.DuplicateHoldException e) {
            // Lưới cuối, và trong thực tế gần như không chạm tới: hai request song song cùng
            // holdId thì request thứ hai đã bị Inventory từ chối bằng HOLD_EXPIRED từ trước —
            // lần giữ chỗ đã sang CONVERTED nên không đặt chỗ lần thứ hai được. Chỉ còn đường
            // duy nhất tới đây là một đơn cũ cùng holdId xuất hiện giữa chừng. Bù trừ phần của
            // mình rồi trả lại đơn đang có, để với khách cả hai lần bấm đều thành công.
            log.info("Đơn cho lần giữ chỗ {} đã tồn tại, bù trừ saga {}", cmd.holdId(), orderId);
            compensate(saga, "Đơn trùng cho cùng một lần giữ chỗ");
            return orders.findByHoldId(cmd.holdId())
                    .map(this::resultOf)
                    .orElseThrow(() ->
                            new ApiException(OrderingErrorCode.CHECKOUT_UNAVAILABLE, "Checkout failed, please retry"));
        }

        return resultOf(order);
    }

    /**
     * Chia giảm giá và hoa hồng xuống từng dòng.
     *
     * <p>Giảm giá được cấp cho cả đơn nhưng phải nằm ở dòng, vì hoàn tiền có thể chỉ hoàn một vé.
     * Chia theo tỷ lệ giá, và <b>dồn phần dư vào dòng cuối</b>: chia 10.000đ cho 3 vé bằng nhau
     * cho 3.333×3 = 9.999, thiếu 1 đồng. Một đồng đó phải đi đâu đó, nếu không tổng các dòng khác
     * tổng đơn và sổ cái kép sẽ không cân.
     */
    private List<OrderItem> buildItems(
            InventoryPort.Reservation reservation, PricingPort.Pricing quote, long subtotalVnd) {
        List<InventoryPort.Seat> seats = reservation.seats();
        List<OrderItem> items = new ArrayList<>(seats.size());
        long allocated = 0;

        for (int i = 0; i < seats.size(); i++) {
            InventoryPort.Seat seat = seats.get(i);
            boolean last = i == seats.size() - 1;
            long discount = last
                    ? quote.discountVnd() - allocated
                    : (subtotalVnd == 0 ? 0 : Math.floorDiv(quote.discountVnd() * seat.priceVnd(), subtotalVnd));
            allocated += discount;

            items.add(new OrderItem(
                    UUID.randomUUID(),
                    seat.sessionSeatId(),
                    seat.seatCode(),
                    seat.zoneCode(),
                    seat.admissionType(),
                    seat.seatLabel(),
                    seat.ticketTypeId(),
                    seat.ticketTypeName(),
                    Money.ofVnd(seat.priceVnd()),
                    Money.ofVnd(discount),
                    quote.commissionBps()));
        }
        return items;
    }

    /**
     * Bù trừ ngay trong luồng đồng bộ — lưới an toàn thứ nhất.
     *
     * <p>Bù trừ theo thứ tự <b>ngược</b> với lúc làm. Nếu chính bù trừ cũng hỏng, saga chuyển sang
     * {@code COMPENSATION_PENDING} và job quét mỗi 30 giây sẽ thử lại (lưới thứ hai). Kể cả job
     * cũng hỏng thì {@code payment_expires_at} 15 phút vẫn nhả ghế (lưới thứ ba).
     */
    private void compensate(CheckoutSaga saga, String reason) {
        if (!saga.hasSomethingToCompensate()) {
            saga.failedBeforeAnyEffect(reason);
            tx.updateSaga(saga);
            return;
        }
        try {
            if (saga.isPaymentIntentOpen()) {
                payments.cancelIntent(saga.orderId());
            }
            if (saga.isSeatsReserved()) {
                inventory.cancelReservation(saga.orderId());
            }
            saga.compensated();
        } catch (RuntimeException e) {
            log.error("Bù trừ saga {} hỏng, chuyển sang COMPENSATION_PENDING", saga.orderId(), e);
            saga.compensationFailed(e.toString());
        }
        tx.updateSaga(saga);
    }

    /**
     * Trả lại đơn đã có, kèm đúng mã QR và link thanh toán khách đang nhìn.
     *
     * <p>Đọc từ snapshot trong đơn chứ không gọi lại payment-service: mã QR đã hiện cho khách không
     * được đổi, và việc mở lại trang đơn hàng không nên phụ thuộc payment-service còn sống.
     */
    private Result resultOf(Order order) {
        return new Result(
                order.id(),
                order.orderNumber().value(),
                order.total().amountVnd(),
                order.paymentReference(),
                order.vietQrPayload(),
                order.checkoutUrl(),
                order.paymentExpiresAt());
    }

    private static OrderingErrorCode mapInventoryCode(String code) {
        return switch (code) {
            case "HOLD_EXPIRED", "HOLD_NOT_FOUND", "HOLD_NOT_OWNED" -> OrderingErrorCode.HOLD_EXPIRED;
            default -> OrderingErrorCode.SEAT_UNAVAILABLE;
        };
    }
}
