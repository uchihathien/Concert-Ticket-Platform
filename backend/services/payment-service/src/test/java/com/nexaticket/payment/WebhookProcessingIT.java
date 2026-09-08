// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.payment.application.command.ExpireIntentsJob;
import com.nexaticket.payment.application.command.HandleBankTransferHandler;
import com.nexaticket.payment.application.command.OpenPaymentIntentHandler;
import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.model.EscrowBankAccount;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.WebhookOutcome;
import com.nexaticket.payment.domain.port.EscrowAccountRepository;
import com.nexaticket.payment.support.FakeOrdering;
import com.nexaticket.payment.support.PaymentTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Xử lý webhook đầu-cuối với PostgreSQL thật.
 *
 * <p>{@link com.nexaticket.payment.domain.TransferClassificationTest} đã phủ ma trận phân loại như
 * hàm thuần. Ở đây kiểm những thứ <b>chỉ database mới chứng minh được</b>: chống trùng khi hai
 * webhook đến song song, và race giữa webhook với worker hết hạn.
 */
class WebhookProcessingIT extends PaymentTestBase {

    @Autowired
    HandleBankTransferHandler handler;

    @Autowired
    OpenPaymentIntentHandler openIntent;

    @Autowired
    ExpireIntentsJob expireIntents;

    @Autowired
    EscrowAccountRepository accounts;

    @Autowired
    FakeOrdering.Fake ordering;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ordering.confirmed.clear();
        ordering.unavailable = false;
        if (accounts.preferred().isEmpty()) {
            accounts.save(new EscrowBankAccount(
                    UUID.randomUUID(), "970422", "MB Bank", "0123456789", "NEXATICKET JSC", true, true));
        }
    }

    @Test
    @DisplayName("Đường thành công: đơn chuyển sang xác nhận và Ordering được báo đúng một lần")
    void duong_thanh_cong() {
        var intent = newIntent(3_000_000L);

        var outcome = handler.handle(transfer("tx-" + UUID.randomUUID(), intent, 3_000_000L), "hash");

        assertThat(outcome).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(ordering.confirmed).containsExactly(intent.orderId());
    }

    @Test
    @DisplayName("Webhook trùng đến TUẦN TỰ: lần hai là DUPLICATE, không báo Ordering lần nữa")
    void trung_lap_tuan_tu() {
        var intent = newIntent(3_000_000L);
        String txId = "tx-" + UUID.randomUUID();

        assertThat(handler.handle(transfer(txId, intent, 3_000_000L), "hash")).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(handler.handle(transfer(txId, intent, 3_000_000L), "hash")).isEqualTo(WebhookOutcome.DUPLICATE);

        // Báo hai lần thì ticketing-service phát vé đôi.
        assertThat(ordering.confirmed).hasSize(1);
    }

    @Test
    @DisplayName("Webhook trùng đến SONG SONG: đúng một lần CONFIRMED, phần còn lại DUPLICATE")
    void trung_lap_song_song() throws Exception {
        // Đây là race mà unique index trên payment_attempts KHÔNG bắt được (BRAINSTORM §4.3):
        // dòng attempt được tạo từ lúc mở intent với trạng thái PENDING và chưa có transaction
        // id, nên hai webhook song song cùng UPDATE một dòng và constraint không kích hoạt lần
        // nào. Bảng webhook_events với INSERT ... ON CONFLICT DO NOTHING mới đóng được.
        var intent = newIntent(3_000_000L);
        String txId = "tx-" + UUID.randomUUID();

        int racers = 8;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<WebhookOutcome>> jobs = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            jobs.add(() -> {
                ready.countDown();
                go.await();
                return handler.handle(transfer(txId, intent, 3_000_000L), "hash");
            });
        }

        List<WebhookOutcome> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Future<WebhookOutcome>> futures = new ArrayList<>();
            for (Callable<WebhookOutcome> job : jobs) {
                futures.add(pool.submit(job));
            }
            ready.await();
            go.countDown();
            for (Future<WebhookOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (Exception e) {
                    outcomes.add(null);
                }
            }
        }

        assertThat(outcomes).filteredOn(o -> o == WebhookOutcome.CONFIRMED).hasSize(1);
        assertThat(ordering.confirmed).hasSize(1);
    }

    @Test
    @DisplayName("Webhook đến đúng lúc worker hết hạn chạy: một bên thắng, không có trạng thái nửa vời")
    void webhook_dua_voi_worker_het_han() {
        // Race kinh điển ở BRAINSTORM §4.4. FOR UPDATE trên payment_intents bắt hai bên xếp
        // hàng, nên bên chạy sau luôn đọc được trạng thái mới nhất thay vì ghi đè.
        var intent = newIntent(3_000_000L);
        jdbc.update("UPDATE payment_intents SET expires_at = now() - interval '1 second' WHERE id = ?", intent.id());

        expireIntents.runOnce();
        var outcome = handler.handle(transfer("tx-" + UUID.randomUUID(), intent, 3_000_000L), "hash");

        // Tiền về sau khi hết hạn thì phải có người xử lý, không được tự động xác nhận.
        assertThat(outcome).isEqualTo(WebhookOutcome.MANUAL_REVIEW);
        assertThat(ordering.confirmed).isEmpty();
    }

    @Test
    @DisplayName("Ordering hỏng: transaction rollback cả dòng chống trùng để nhà cung cấp gửi lại")
    void ordering_hong_thi_rollback_ca_dong_chong_trung() {
        var intent = newIntent(3_000_000L);
        String txId = "tx-" + UUID.randomUUID();
        ordering.unavailable = true;

        try {
            handler.handle(transfer(txId, intent, 3_000_000L), "hash");
        } catch (RuntimeException expected) {
            // Ném lên là đúng: controller sẽ trả 503 và SePay gửi lại.
        }

        // Nếu dòng dedupe còn sót lại, lần gửi lại sẽ bị coi là DUPLICATE và đơn đã trả tiền
        // sẽ mãi không được xác nhận — không ai retry nữa vì ta đã "xử lý" nó.
        ordering.unavailable = false;
        assertThat(handler.handle(transfer(txId, intent, 3_000_000L), "hash")).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(ordering.confirmed).containsExactly(intent.orderId());
    }

    @Test
    @DisplayName("Giao dịch không khớp vẫn được ghi lại — không có giao dịch vô chủ")
    void giao_dich_khong_khop_van_duoc_ghi() {
        var transfer =
                new BankTransfer("tx-" + UUID.randomUUID(), "CHUYEN TIEN LINH TINH", 500_000L, "970422", "0123456789");

        assertThat(handler.handle(transfer, "hash")).isEqualTo(WebhookOutcome.MANUAL_REVIEW);

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE outcome = 'MANUAL_REVIEW' AND raw_reference LIKE ?",
                Integer.class,
                "%LINH TINH%");
        assertThat(pending).isEqualTo(1);
    }

    @Test
    @DisplayName("Mở intent hai lần cho cùng một đơn: trả lại đúng mã QR cũ")
    void mo_intent_idempotent() {
        UUID orderId = UUID.randomUUID();
        var first = openIntent.handle(command(orderId, 1_000_000L));
        var second = openIntent.handle(command(orderId, 1_000_000L));

        // Sinh mã mới sẽ để lại hai mã tham chiếu cùng trỏ một đơn, và nếu khách đã quét mã
        // đầu thì tiền về với mã mà hệ thống vừa quên mất.
        assertThat(second.reference().value()).isEqualTo(first.reference().value());
        assertThat(second.vietQrPayload()).isEqualTo(first.vietQrPayload());
    }

    private PaymentIntent newIntent(long amountVnd) {
        return openIntent.handle(command(UUID.randomUUID(), amountVnd));
    }

    private static OpenPaymentIntentHandler.Command command(UUID orderId, long amountVnd) {
        return new OpenPaymentIntentHandler.Command(
                orderId, UUID.randomUUID(), amountVnd, Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private static BankTransfer transfer(String txId, PaymentIntent intent, long amountVnd) {
        return new BankTransfer(
                txId, "CK " + intent.reference().value(), amountVnd, intent.bankBin(), intent.bankAccountNumber());
    }
}
