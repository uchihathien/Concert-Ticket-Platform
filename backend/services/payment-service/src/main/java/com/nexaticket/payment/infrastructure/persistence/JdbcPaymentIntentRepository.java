// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.persistence;

import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Adapter persistence. JdbcTemplate chứ không JPA — cùng lý lẽ với các service khác. */
@Repository
public class JdbcPaymentIntentRepository implements PaymentIntentRepository {

    private final JdbcTemplate jdbc;

    public JdbcPaymentIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextOrderCode() {
        Long next = jdbc.queryForObject("SELECT nextval('payment_order_code_seq')", Long.class);
        if (next == null) {
            throw new IllegalStateException("nextval('payment_order_code_seq') trả null");
        }
        return next;
    }

    /**
     * Đóng một lô intent quá hạn.
     *
     * <p>Một câu UPDATE có điều kiện, không đọc-rồi-ghi: giữa hai bước đó webhook có thể vừa xác
     * nhận một khoản tiền thật, và ghi đè nó bằng EXPIRED là xoá dấu vết của khoản tiền đó.
     *
     * <p>{@code IN (SELECT … LIMIT …)} để một lần chạy không khoá cả bảng khi có tồn đọng lớn.
     */
    @Override
    public int expirePending(java.time.Instant now, int batchSize) {
        return jdbc.update(
                """
                UPDATE payment_intents
                   SET status = 'EXPIRED', cancelled_at = ?
                 WHERE order_id IN (
                       SELECT order_id FROM payment_intents
                        WHERE status = 'PENDING' AND expires_at <= ?
                        ORDER BY expires_at
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED)
                   AND status = 'PENDING'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize);
    }

    /**
     * {@code ON CONFLICT DO NOTHING} rồi đọc lại, chứ không "kiểm tra rồi ghi".
     *
     * <p>Hai request song song cùng {@code orderId} — saga chạy lại sau timeout — sẽ cùng thấy
     * "chưa có" nếu kiểm trước, rồi cả hai cùng ghi và một cái vỡ vì trùng khoá chính. Cách này để
     * database quyết định ai thắng, và cả hai bên đều nhận về đúng một intent.
     *
     * <p>Người gọi <b>phải</b> so {@code payosOrderCode} của bản trả về với bản mình vừa dựng: thua
     * cuộc nghĩa là link payOS vừa tạo không được dùng, và một link sống không ai biết tới là một
     * khoản tiền sẽ vào rồi mồ côi.
     */
    @Override
    public PaymentIntent insertIfAbsent(PaymentIntent intent) {
        jdbc.update(
                """
                INSERT INTO payment_intents (
                    order_id, organization_id, reference, amount_vnd, status,
                    vietqr_payload, bank_bin, bank_account_number, bank_account_name,
                    payos_order_code, payos_payment_link_id, checkout_url, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """,
                intent.orderId(),
                intent.organizationId(),
                intent.reference().value(),
                intent.amountVnd(),
                intent.status().name(),
                intent.vietQrPayload(),
                intent.bankBin(),
                intent.bankAccountNumber(),
                intent.bankAccountName(),
                intent.payosOrderCode(),
                intent.payosPaymentLinkId(),
                intent.checkoutUrl(),
                Timestamp.from(intent.expiresAt()));

        return findByOrder(intent.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Vừa ghi xong mà không đọc lại được intent của đơn " + intent.orderId()));
    }

    @Override
    public Optional<PaymentIntent> findByOrder(UUID orderId) {
        return first(jdbc.query(selectBy("order_id"), JdbcPaymentIntentRepository::hydrate, orderId));
    }

    @Override
    public Optional<PaymentIntent> findByReference(String reference) {
        return first(jdbc.query(selectBy("reference"), JdbcPaymentIntentRepository::hydrate, reference));
    }

    @Override
    public Optional<PaymentIntent> findByPayosOrderCode(long payosOrderCode) {
        return first(jdbc.query(selectBy("payos_order_code"), JdbcPaymentIntentRepository::hydrate, payosOrderCode));
    }

    @Override
    public Optional<UUID> orderOfTransaction(String provider, String providerTxnId) {
        return jdbc
                .query(
                        "SELECT order_id FROM payment_intents WHERE provider = ? AND provider_txn_id = ?",
                        (rs, i) -> rs.getObject("order_id", UUID.class),
                        provider,
                        providerTxnId)
                .stream()
                .findFirst();
    }

    /**
     * {@code WHERE status = 'PENDING'} là phần quan trọng nhất của câu này.
     *
     * <p>Không có nó, job quét hết hạn và webhook payOS chạy cùng lúc sẽ ghi đè nhau: cả hai đọc thấy
     * PENDING, cả hai ghi, và nếu job ghi sau thì một khoản tiền đã vào bị đánh dấu EXPIRED trong im
     * lặng. READ COMMITTED không chặn được việc đó, chỉ một câu UPDATE có điều kiện mới chặn.
     */
    @Override
    public boolean updateStatusIfPending(PaymentIntent intent) {
        int updated = jdbc.update(
                """
                UPDATE payment_intents
                   SET status = ?, provider = ?, provider_txn_id = ?, paid_amount_vnd = ?,
                       confirmed_at = ?, cancelled_at = ?
                 WHERE order_id = ? AND status = 'PENDING'
                """,
                intent.status().name(),
                intent.provider(),
                intent.providerTxnId(),
                intent.paidAmountVnd(),
                intent.confirmedAt() == null ? null : Timestamp.from(intent.confirmedAt()),
                intent.cancelledAt() == null ? null : Timestamp.from(intent.cancelledAt()),
                intent.orderId());
        return updated == 1;
    }

    private static String selectBy(String column) {
        return """
               SELECT order_id, organization_id, reference, amount_vnd, status, vietqr_payload,
                      bank_bin, bank_account_number, bank_account_name, payos_order_code,
                      payos_payment_link_id, checkout_url, expires_at, provider, provider_txn_id,
                      paid_amount_vnd, confirmed_at, cancelled_at
                 FROM payment_intents WHERE %s = ?
               """
                .formatted(column);
    }

    private static Optional<PaymentIntent> first(List<PaymentIntent> rows) {
        return rows.stream().findFirst();
    }

    private static PaymentIntent hydrate(ResultSet rs, int rowNum) throws SQLException {
        return PaymentIntent.rehydrate(
                rs.getObject("order_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("reference"),
                rs.getLong("amount_vnd"),
                rs.getString("vietqr_payload"),
                rs.getString("bank_bin"),
                rs.getString("bank_account_number"),
                rs.getString("bank_account_name"),
                rs.getLong("payos_order_code"),
                rs.getString("payos_payment_link_id"),
                rs.getString("checkout_url"),
                rs.getTimestamp("expires_at").toInstant(),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("provider"),
                rs.getString("provider_txn_id"),
                rs.getObject("paid_amount_vnd", Long.class),
                instant(rs, "confirmed_at"),
                instant(rs, "cancelled_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
