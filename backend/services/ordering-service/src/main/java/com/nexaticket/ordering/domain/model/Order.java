// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

import com.nexaticket.kernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate đơn hàng.
 *
 * <p>Tổng tiền và hoa hồng <b>được tính từ các dòng</b>, không nhận từ ngoài vào. Nếu để caller
 * truyền tổng, thì một lỗi làm tròn ở tầng nào đó sẽ đi thẳng vào sổ cái mà không ai chặn.
 */
public final class Order {

    private final UUID id;
    private final OrderNumber orderNumber;
    private final UUID eventSessionId;
    private final UUID organizationId;
    private final UUID userId;
    private final UUID holdId;
    private final List<OrderItem> items;
    private final String promotionCode;
    private final Instant paymentExpiresAt;

    private OrderStatus status;
    private String paymentReference;
    private String vietQrPayload;
    private Instant paidAt;
    private Instant closedAt;
    private String closeReason;

    private Order(
            UUID id,
            OrderNumber orderNumber,
            UUID eventSessionId,
            UUID organizationId,
            UUID userId,
            UUID holdId,
            List<OrderItem> items,
            String promotionCode,
            Instant paymentExpiresAt,
            OrderStatus status) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng rỗng không có nghĩa");
        }
        this.id = id;
        this.orderNumber = orderNumber;
        this.eventSessionId = eventSessionId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.holdId = holdId;
        this.items = List.copyOf(items);
        this.promotionCode = promotionCode;
        this.paymentExpiresAt = paymentExpiresAt;
        this.status = status;
    }

    public static Order awaitingPayment(
            UUID id,
            UUID eventSessionId,
            UUID organizationId,
            UUID userId,
            UUID holdId,
            List<OrderItem> items,
            String promotionCode,
            Instant now,
            java.time.Duration paymentWindow) {
        return new Order(
                id,
                OrderNumber.generate(now),
                eventSessionId,
                organizationId,
                userId,
                holdId,
                items,
                promotionCode,
                now.plus(paymentWindow),
                OrderStatus.AWAITING_PAYMENT);
    }

    @SuppressWarnings("java:S107") // Rehydrate từ database cần đủ mọi cột; gom thành DTO chỉ đổi chỗ đặt tham số.
    public static Order rehydrate(
            UUID id,
            String orderNumber,
            UUID eventSessionId,
            UUID organizationId,
            UUID userId,
            UUID holdId,
            List<OrderItem> items,
            String promotionCode,
            Instant paymentExpiresAt,
            OrderStatus status,
            String paymentReference,
            String vietQrPayload,
            Instant paidAt) {
        Order order = new Order(
                id,
                new OrderNumber(orderNumber),
                eventSessionId,
                organizationId,
                userId,
                holdId,
                items,
                promotionCode,
                paymentExpiresAt,
                status);
        order.paymentReference = paymentReference;
        order.vietQrPayload = vietQrPayload;
        order.paidAt = paidAt;
        return order;
    }

    public Money subtotal() {
        return items.stream().map(OrderItem::unitPrice).reduce(Money.ZERO, Money::plus);
    }

    public Money discount() {
        return items.stream().map(OrderItem::discount).reduce(Money.ZERO, Money::plus);
    }

    public Money total() {
        return subtotal().minus(discount());
    }

    /**
     * Hoa hồng cộng từ <b>từng dòng</b>, không phải tính một lần trên tổng.
     *
     * <p>Hai cách cho kết quả lệch nhau khi làm tròn: 3 dòng 333.333đ ở 5% cho 16.666×3 = 49.998,
     * còn tính trên tổng 999.999 cho 49.999. Chênh 1 đồng, nhưng sổ cái kép sẽ không cân và
     * constraint trigger của ledger-service sẽ từ chối cả bút toán. Cộng từ dòng vì vé được phát
     * theo dòng, và hoàn tiền cũng theo dòng.
     */
    public Money commission() {
        return items.stream().map(OrderItem::commission).reduce(Money.ZERO, Money::plus);
    }

    /** Tỷ lệ hoa hồng hiệu lực của cả đơn, chỉ để hiển thị và đối soát. */
    public int commissionBps() {
        return items.isEmpty() ? 0 : items.get(0).commissionBps();
    }

    public boolean isExpiredAt(Instant now) {
        return status == OrderStatus.AWAITING_PAYMENT && !now.isBefore(paymentExpiresAt);
    }

    /**
     * Gắn kết quả của payment-service vào đơn.
     *
     * <p>Cả hai giá trị là snapshot: mã QR khách đã nhìn thấy không bao giờ được đổi, kể cả khi
     * nền tảng đổi tài khoản ký quỹ sau đó.
     */
    public void attachPayment(String reference, String vietQrPayload) {
        this.paymentReference = reference;
        this.vietQrPayload = vietQrPayload;
    }

    /**
     * Xác nhận đã nhận tiền.
     *
     * <p>Idempotent: webhook của SePay có thể đến hai lần, và lần thứ hai không được đổi
     * {@code paidAt} — mốc thời gian đó đi vào sổ cái và vào hạn giữ tiền.
     *
     * @return true nếu lần gọi này thực sự đổi trạng thái
     */
    public boolean markPaid(Instant now) {
        if (status == OrderStatus.PAID) {
            return false;
        }
        if (status != OrderStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException("Không thể thanh toán đơn ở trạng thái " + status);
        }
        status = OrderStatus.PAID;
        paidAt = now;
        return true;
    }

    /**
     * Đóng đơn chưa thanh toán.
     *
     * @return true nếu lần gọi này thực sự đóng đơn; false nếu đơn đã đóng hoặc đã PAID
     */
    public boolean close(OrderStatus reason, Instant now, String note) {
        if (reason.isOpen()) {
            throw new IllegalArgumentException("Lý do đóng đơn không hợp lệ: " + reason);
        }
        // Đơn đã PAID không bao giờ bị worker hết hạn đóng: tiền đã vào tài khoản ta rồi.
        if (status != OrderStatus.AWAITING_PAYMENT) {
            return false;
        }
        status = reason;
        closedAt = now;
        closeReason = note;
        return true;
    }

    public UUID id() {
        return id;
    }

    public OrderNumber orderNumber() {
        return orderNumber;
    }

    public UUID eventSessionId() {
        return eventSessionId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID holdId() {
        return holdId;
    }

    public List<OrderItem> items() {
        return items;
    }

    public String promotionCode() {
        return promotionCode;
    }

    public Instant paymentExpiresAt() {
        return paymentExpiresAt;
    }

    public OrderStatus status() {
        return status;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public String vietQrPayload() {
        return vietQrPayload;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public String closeReason() {
        return closeReason;
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }
}
