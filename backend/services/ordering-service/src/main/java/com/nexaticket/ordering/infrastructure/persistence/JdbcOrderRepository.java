// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.persistence;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderItem;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository implements OrderRepository {

    private final JdbcTemplate jdbc;

    public JdbcOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Order order) {
        try {
            jdbc.update(
                    """
                    INSERT INTO orders (id, order_number, event_session_id, event_id, organization_id,
                                        user_id, hold_id, status, subtotal_vnd, discount_vnd, total_vnd,
                                        commission_bps, commission_vnd, promotion_code,
                                        payment_reference, vietqr_payload, checkout_url,
                                        payment_expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    order.id(),
                    order.orderNumber().value(),
                    order.eventSessionId(),
                    order.eventId(),
                    order.organizationId(),
                    order.userId(),
                    order.holdId(),
                    order.status().name(),
                    order.subtotal().amountVnd(),
                    order.discount().amountVnd(),
                    order.total().amountVnd(),
                    order.commissionBps(),
                    order.commission().amountVnd(),
                    order.promotionCode(),
                    order.paymentReference(),
                    order.vietQrPayload(),
                    order.checkoutUrl(),
                    Timestamp.from(order.paymentExpiresAt()));
        } catch (DuplicateKeyException e) {
            // uq orders.hold_id — hai request song song cùng một lần giữ chỗ.
            throw new DuplicateHoldException(e);
        }

        jdbc.batchUpdate(
                """
                INSERT INTO order_items (id, order_id, session_seat_id, seat_code, zone_code,
                                         admission_type, seat_label, ticket_type_id, ticket_type_name,
                                         unit_price_vnd, discount_vnd, commission_bps, commission_vnd)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                order.items().stream()
                        .map(i -> new Object[] {
                            i.id(),
                            order.id(),
                            i.sessionSeatId(),
                            i.seatCode(),
                            i.zoneCode(),
                            i.admissionType(),
                            i.seatLabel(),
                            i.ticketTypeId(),
                            i.ticketTypeName(),
                            i.unitPrice().amountVnd(),
                            i.discount().amountVnd(),
                            i.commissionBps(),
                            i.commission().amountVnd()
                        })
                        .toList());
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return queryOne("WHERE id = ?", orderId);
    }

    @Override
    public Optional<Order> findByHoldId(UUID holdId) {
        return queryOne("WHERE hold_id = ?", holdId);
    }

    @Override
    public Optional<Order> findByPaymentReference(String paymentReference) {
        return queryOne("WHERE payment_reference = ?", paymentReference);
    }

    @Override
    public List<Order> findByUser(UUID userId, int limit, int offset) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM orders WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> rs.getObject("id", UUID.class),
                userId,
                limit,
                offset);
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public void updateStatus(Order order) {
        jdbc.update(
                """
                UPDATE orders SET status = ?, paid_at = ?, closed_at = ?, close_reason = ?
                 WHERE id = ?
                """,
                order.status().name(),
                order.paidAt() == null ? null : Timestamp.from(order.paidAt()),
                order.closedAt() == null ? null : Timestamp.from(order.closedAt()),
                order.closeReason(),
                order.id());
    }

    @Override
    public void attachPaymentReference(UUID orderId, String paymentReference) {
        jdbc.update("UPDATE orders SET payment_reference = ? WHERE id = ?", paymentReference, orderId);
    }

    @Override
    public List<Order> claimExpired(Instant now, int batchSize) {
        // Điều kiện status nằm TRONG câu nhận việc, không kiểm ở Java sau khi đọc: giữa hai
        // thời điểm đó webhook có thể vừa chuyển đơn sang PAID.
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM orders
                 WHERE status = 'AWAITING_PAYMENT' AND payment_expires_at <= ?
                 ORDER BY payment_expires_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                Timestamp.from(now),
                batchSize);
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    private Optional<Order> queryOne(String where, Object arg) {
        List<Order> found = jdbc.query(
                """
                SELECT id, order_number, event_session_id, event_id, organization_id, user_id,
                       hold_id, status, promotion_code, payment_reference, vietqr_payload,
                       checkout_url, payment_expires_at, paid_at
                  FROM orders
                """
                        + where,
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Timestamp paidAt = rs.getTimestamp("paid_at");
                    return Order.rehydrate(
                            id,
                            rs.getString("order_number"),
                            rs.getObject("event_session_id", UUID.class),
                            rs.getObject("event_id", UUID.class),
                            rs.getObject("organization_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("hold_id", UUID.class),
                            itemsOf(id),
                            rs.getString("promotion_code"),
                            rs.getTimestamp("payment_expires_at").toInstant(),
                            OrderStatus.valueOf(rs.getString("status")),
                            rs.getString("payment_reference"),
                            rs.getString("vietqr_payload"),
                            rs.getString("checkout_url"),
                            paidAt == null ? null : paidAt.toInstant());
                },
                arg);
        return found.stream().findFirst();
    }

    private List<OrderItem> itemsOf(UUID orderId) {
        return jdbc.query(
                """
                SELECT id, session_seat_id, seat_code, zone_code, admission_type, seat_label,
                       ticket_type_id, ticket_type_name, unit_price_vnd, discount_vnd, commission_bps
                  FROM order_items WHERE order_id = ? ORDER BY seat_code
                """,
                (rs, i) -> new OrderItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("session_seat_id", UUID.class),
                        rs.getString("seat_code"),
                        rs.getString("zone_code"),
                        rs.getString("admission_type"),
                        rs.getString("seat_label"),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("ticket_type_name"),
                        Money.ofVnd(rs.getLong("unit_price_vnd")),
                        Money.ofVnd(rs.getLong("discount_vnd")),
                        rs.getInt("commission_bps")),
                orderId);
    }
}
