// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexaticket.payment.application.command.ExpireIntentsJob;
import com.nexaticket.payment.application.command.HandlePayosWebhookHandler;
import com.nexaticket.payment.application.command.OpenIntentHandler;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.infrastructure.payos.PayosSignature;
import com.nexaticket.payment.support.FakePayos;
import com.nexaticket.payment.support.PaymentTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Các ca webhook mà chỉ PostgreSQL thật trả lời được: khoá {@code uq_provider_txn} giữa hai đơn, câu
 * {@code UPDATE … WHERE status = 'PENDING'} của job hết hạn gặp webhook tới muộn, và dòng nhật ký của một
 * webhook đã ký nhưng báo thất bại.
 *
 * <p>Không TRUNCATE: mọi khẳng định ở đây chỉ nhìn vào intent và mã giao dịch do chính lần chạy này sinh ra,
 * nên dữ liệu cũ trong container {@code withReuse(true)} không làm lệch kết quả.
 */
class WebhookEdgeCasesIT extends PaymentTestBase {

    private static final long AMOUNT = 1_500_000L;

    /** Mã giao dịch duy nhất cho mỗi lần chạy — xem giải thích ở {@code PayosPaymentFlowIT.TXN_PREFIX}. */
    private static final String TXN_PREFIX = "EC"
            + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40))
                    .toUpperCase(java.util.Locale.ROOT);

    @Autowired
    OpenIntentHandler openIntent;

    @Autowired
    HandlePayosWebhookHandler webhook;

    @Autowired
    ExpireIntentsJob expireIntents;

    @Autowired
    PaymentIntentRepository intents;

    @Autowired
    FakePayos.FakePayosGateway payos;

    @Autowired
    FakePayos.FakeOrdering ordering;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        payos.reset();
        ordering.reset();
    }

    private long open(UUID orderId, Instant expiresAt) {
        openIntent.handle(new OpenIntentHandler.Command(orderId, UUID.randomUUID(), AMOUNT, expiresAt));
        return intents.findByOrder(orderId).orElseThrow().payosOrderCode();
    }

    @Test
    @DisplayName("Cùng một mã giao dịch ngân hàng cho đơn thứ hai: DUPLICATE, đơn thứ hai KHÔNG được xác nhận")
    void ma_giao_dich_dung_lai_cho_don_khac() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        long firstCode = open(first, Instant.now().plus(Duration.ofMinutes(15)));
        long secondCode = open(second, Instant.now().plus(Duration.ofMinutes(15)));
        String txn = TXN_PREFIX + "-REPLAY";

        HandlePayosWebhookHandler.Outcome a = webhook.handle(signedWebhook(firstCode, AMOUNT, txn, "00", true));
        HandlePayosWebhookHandler.Outcome b = webhook.handle(signedWebhook(secondCode, AMOUNT, txn, "00", true));

        assertThat(a.outcome()).isEqualTo("CONFIRMED");
        assertThat(b.authentic()).isTrue();
        assertThat(b.outcome()).isEqualTo("DUPLICATE");
        assertThat(intents.findByOrder(second).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(ordering.confirmed).containsExactly(first);
        assertThat(outcomesOf(secondCode)).containsExactly("DUPLICATE");
    }

    @Test
    @DisplayName("Job hết hạn chạy trước, webhook tới sau: NOT_PENDING, không báo Ordering, intent vẫn EXPIRED")
    void webhook_toi_sau_khi_het_han() {
        UUID orderId = UUID.randomUUID();
        long code = open(orderId, Instant.now().minus(Duration.ofMinutes(1)));

        expireIntents.runOnce();
        HandlePayosWebhookHandler.Outcome outcome =
                webhook.handle(signedWebhook(code, AMOUNT, TXN_PREFIX + "-LATE", "00", true));

        assertThat(outcome.authentic()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("NOT_PENDING");
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(ordering.confirmed).doesNotContain(orderId);
        assertThat(outcomesOf(code)).containsExactly("NOT_PENDING");
    }

    @Test
    @DisplayName("Webhook đã ký báo thất bại: nhận, ghi REJECTED kèm orderCode và số tiền, intent vẫn PENDING")
    void webhook_bao_that_bai() {
        UUID orderId = UUID.randomUUID();
        long code = open(orderId, Instant.now().plus(Duration.ofMinutes(15)));

        HandlePayosWebhookHandler.Outcome outcome =
                webhook.handle(signedWebhook(code, AMOUNT, TXN_PREFIX + "-FAIL", "01", false));

        assertThat(outcome.authentic()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("REJECTED");
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(ordering.confirmed).isEmpty();
        Long loggedAmount = jdbc.queryForObject(
                "SELECT amount_vnd FROM bank_webhook_log WHERE payos_order_code = ?", Long.class, code);
        assertThat(loggedAmount).isEqualTo(AMOUNT);
    }

    private List<String> outcomesOf(long orderCode) {
        return jdbc.queryForList(
                "SELECT outcome FROM bank_webhook_log WHERE payos_order_code = ? ORDER BY received_at",
                String.class,
                orderCode);
    }

    /** Webhook đã ký thật bằng checksum key của test — cùng cách với {@code PayosPaymentFlowIT}. */
    private static String signedWebhook(long orderCode, long amountVnd, String reference, String code, boolean ok) {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("orderCode", orderCode);
        data.put("amount", amountVnd);
        data.put("description", "NT" + orderCode);
        data.put("accountNumber", "V3CAS" + orderCode);
        data.put("reference", reference);
        data.put("transactionDateTime", "2026-09-09 10:25:00");
        data.put("currency", "VND");
        data.put("paymentLinkId", "link-" + orderCode);
        data.put("code", code);
        data.put("desc", ok ? "Thanh cong" : "That bai");

        String signature = PayosSignature.sign(data, PaymentTestBase.CHECKSUM_KEY);
        return "{\"code\":\"%s\",\"desc\":\"%s\",\"success\":%s,\"data\":%s,\"signature\":\"%s\"}"
                .formatted(code, ok ? "success" : "failed", ok, data, signature);
    }
}
