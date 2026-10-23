// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Đề xuất đặt vé trên PostgreSQL.
 *
 * <p>Mỗi phép chuyển trạng thái là một UPDATE có {@code status = 'PROPOSED'} trong WHERE — xem hợp
 * đồng ở {@link BookingProposalRepository}. {@code insert} là INSERT thường: trùng đề xuất mở thì
 * {@code DuplicateKeyException} lọt ra, vì người gọi cần biết điều đó, không phải "bỏ qua".
 */
@Repository
public class JdbcBookingProposalRepository implements BookingProposalRepository {

    private static final String COLUMNS =
            """
            id, user_id, chat_session_id, event_slug, event_session_id, zone_code, quantity,
            unit_price_vnd, total_vnd, status, order_id, order_number, checkout_url, payment_expires_at,
            expires_at, created_at, confirmed_at
            """;

    private final JdbcClient db;

    public JdbcBookingProposalRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insert(BookingProposal p) {
        db.sql(
                        """
                        insert into booking_proposals
                            (id, user_id, chat_session_id, event_slug, event_session_id, zone_code, quantity,
                             unit_price_vnd, total_vnd, status, order_id, order_number, checkout_url,
                             payment_expires_at, expires_at, created_at, confirmed_at)
                        values (:id, :userId, :chatSessionId, :eventSlug, :eventSessionId, :zoneCode, :quantity,
                                :unitPriceVnd, :totalVnd, :status, :orderId, :orderNumber, :checkoutUrl,
                                :paymentExpiresAt, :expiresAt, :createdAt, :confirmedAt)
                        """)
                .param("id", p.id())
                .param("userId", p.userId())
                .param("chatSessionId", p.chatSessionId())
                .param("eventSlug", p.eventSlug())
                .param("eventSessionId", p.eventSessionId())
                .param("zoneCode", p.zoneCode())
                .param("quantity", p.quantity())
                .param("unitPriceVnd", p.unitPriceVnd())
                .param("totalVnd", p.totalVnd())
                .param("status", p.status().name())
                .param("orderId", p.order() == null ? null : p.order().orderId())
                .param("orderNumber", p.order() == null ? null : p.order().orderNumber())
                .param("checkoutUrl", p.order() == null ? null : p.order().checkoutUrl())
                .param(
                        "paymentExpiresAt",
                        p.order() == null ? null : timestampOrNull(p.order().paymentExpiresAt()))
                .param("expiresAt", Timestamp.from(p.expiresAt()))
                .param("createdAt", Timestamp.from(p.createdAt()))
                .param("confirmedAt", timestampOrNull(p.confirmedAt()))
                .update();
    }

    @Override
    public Optional<BookingProposal> findById(UUID id) {
        return db.sql("select " + COLUMNS + " from booking_proposals where id = :id")
                .param("id", id)
                .query(JdbcBookingProposalRepository::map)
                .optional();
    }

    @Override
    public int cancelOpen(UUID userId, UUID eventSessionId, String zoneCode, Instant now) {
        return db.sql(
                        """
                        update booking_proposals set status = 'CANCELLED'
                         where user_id = :userId and event_session_id = :eventSessionId and zone_code = :zoneCode
                           and status = 'PROPOSED'
                        """)
                .param("userId", userId)
                .param("eventSessionId", eventSessionId)
                .param("zoneCode", zoneCode)
                .update();
    }

    @Override
    public boolean cancel(UUID id, UUID userId, Instant now) {
        return db.sql(
                                """
                        update booking_proposals set status = 'CANCELLED'
                         where id = :id and user_id = :userId and status = 'PROPOSED'
                        """)
                        .param("id", id)
                        .param("userId", userId)
                        .update()
                == 1;
    }

    @Override
    public boolean markConfirmed(UUID id, BookingProposal.Order order, Instant now) {
        return db.sql(
                                """
                        update booking_proposals
                           set status = 'CONFIRMED',
                               order_id = :orderId,
                               order_number = :orderNumber,
                               checkout_url = :checkoutUrl,
                               payment_expires_at = :paymentExpiresAt,
                               confirmed_at = :now
                         where id = :id and status = 'PROPOSED'
                        """)
                        .param("orderId", order.orderId())
                        .param("orderNumber", order.orderNumber())
                        .param("checkoutUrl", order.checkoutUrl())
                        .param("paymentExpiresAt", timestampOrNull(order.paymentExpiresAt()))
                        .param("now", Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    @Override
    public int expireOverdue(Instant now) {
        // Chỉ quét phần đang mở (idx_booking_proposals_expiry); `<=` để đề xuất hết hạn đúng mốc cũng
        // được chuyển, khớp `isOpen` dùng `expiresAt.isAfter(now)`.
        return db.sql(
                        "update booking_proposals set status = 'EXPIRED' where status = 'PROPOSED' and expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public List<BookingProposal> recentForSession(UUID chatSessionId, UUID userId, int limit) {
        return db.sql("select " + COLUMNS
                        + " from booking_proposals where chat_session_id = :s and user_id = :u"
                        + " order by created_at desc, id desc limit :n")
                .param("s", chatSessionId)
                .param("u", userId)
                .param("n", limit)
                .query(JdbcBookingProposalRepository::map)
                .list();
    }

    @Override
    public Map<BookingProposalStatus, Long> countByStatus() {
        Map<BookingProposalStatus, Long> counts = new EnumMap<>(BookingProposalStatus.class);
        for (BookingProposalStatus s : BookingProposalStatus.values()) {
            counts.put(s, 0L);
        }
        db.sql("select status, count(*) as n from booking_proposals group by status")
                .query((rs, rowNum) ->
                        Map.entry(BookingProposalStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private static BookingProposal map(ResultSet rs, int rowNum) throws SQLException {
        UUID orderId = rs.getObject("order_id", UUID.class);
        BookingProposal.Order order = orderId == null
                ? null
                : new BookingProposal.Order(
                        orderId,
                        rs.getString("order_number"),
                        rs.getString("checkout_url"),
                        instantOrNull(rs.getTimestamp("payment_expires_at")));
        return new BookingProposal(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("chat_session_id", UUID.class),
                rs.getString("event_slug"),
                rs.getObject("event_session_id", UUID.class),
                rs.getString("zone_code"),
                rs.getInt("quantity"),
                rs.getLong("unit_price_vnd"),
                rs.getLong("total_vnd"),
                BookingProposalStatus.valueOf(rs.getString("status")),
                order,
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                instantOrNull(rs.getTimestamp("confirmed_at")));
    }

    private static Instant instantOrNull(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestampOrNull(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}
