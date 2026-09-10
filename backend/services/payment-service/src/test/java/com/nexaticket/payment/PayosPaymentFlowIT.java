// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payment.application.command.CancelIntentHandler;
import com.nexaticket.payment.application.command.ConfirmTransferHandler;
import com.nexaticket.payment.application.command.ExpireIntentsJob;
import com.nexaticket.payment.application.command.HandlePayosWebhookHandler;
import com.nexaticket.payment.application.command.OpenIntentHandler;
import com.nexaticket.payment.application.command.ReconcileIntentHandler;
import com.nexaticket.payment.application.command.SimulateTransferHandler;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.payment.infrastructure.payos.PayosSignature;
import com.nexaticket.payment.support.FakePayos;
import com.nexaticket.payment.support.PaymentTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Luồng thu tiền qua payOS, chạy trên schema thật.
 *
 * <p>Những thứ chỉ đổ ở runtime và chỉ đổ trên PostgreSQL thật: tên cột trong {@code SELECT}, sequence
 * {@code payment_order_code_seq}, partial unique index {@code uq_payos_order_code}, {@code ck_webhook_outcome},
 * và câu {@code UPDATE … WHERE status = 'PENDING'}. Đây là chỗ kiểm chúng.
 */
class PayosPaymentFlowIT extends PaymentTestBase {

    private static final long AMOUNT = 3_000_000L;

    /**
     * Tiền tố ngẫu nhiên cho mã giao dịch của <b>mỗi lần chạy</b>.
     *
     * <p>Container PostgreSQL của test {@code withReuse(true)} nên nó sống qua nhiều lần build và
     * <b>giữ lại dữ liệu cũ</b>. Mã giao dịch là khoá chống ghi nhận trùng — partial unique index
     * {@code uq_provider_txn} trên {@code (provider, provider_txn_id)} — nên một hằng số như
     * {@code txn("001")} sẽ khớp đúng giao dịch của lần build TRƯỚC ở lần build sau, và
     * {@code ConfirmTransferHandler} trả về {@code DUPLICATE} thay vì {@code CONFIRMED}.
     *
     * <p>Triệu chứng rất dễ gây hiểu nhầm: test xanh ở lần chạy đầu rồi đỏ ở mọi lần sau, với một thông
     * báo ("đã ghi nhận trước đó") nghe như chính logic chống trùng đang chạy đúng. Mã giao dịch ngân
     * hàng thật là duy nhất toàn cục, nên fixture cũng phải vậy.
     */
    private static final String TXN_PREFIX = "TF"
            + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40))
                    .toUpperCase(java.util.Locale.ROOT);

    /** Mã giao dịch duy nhất cho lần chạy này. */
    private static String txn(String suffix) {
        return TXN_PREFIX + "-" + suffix;
    }

    @Autowired
    OpenIntentHandler openIntent;

    @Autowired
    CancelIntentHandler cancelIntent;

    @Autowired
    ReconcileIntentHandler reconcileIntent;

    @Autowired
    SimulateTransferHandler simulateTransfer;

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

    /**
     * Bàn sạch trước mỗi ca — kể cả bảng, không chỉ hàng giả.
     *
     * <p>Container PostgreSQL được dùng lại giữa các lần chạy ({@code PostgresSingleton} không tắt
     * nó), nên intent của những lần chạy trước còn nguyên trong database. Chúng được mở với hạn 15
     * phút và lặng lẽ quá hạn theo thời gian thật.
     *
     * <p>Điều đó làm hỏng đúng một ca: {@code job_don_intent_qua_han} khẳng định
     * {@code runOnce()} trả về 1, mà {@code runOnce()} quét <b>toàn bộ</b> intent quá hạn của cả
     * database — không có cách nào giới hạn nó theo test, vì đó chính là công việc của job. Triệu
     * chứng là kiểu tệ nhất: xanh khi chạy riêng, đỏ khi chạy cả bộ, và con số trong thông báo lỗi
     * ({@code expected 1 but was 15}) lớn dần theo số lần đã chạy.
     *
     * <p>Cách chữa đúng là dọn bảng, không phải nới khẳng định xuống {@code >= 1}: nới ra thì ca
     * test không còn phát hiện được việc job đụng nhầm vào một intent ĐÃ NHẬN TIỀN — mà đó mới là
     * điều nó sinh ra để canh.
     */
    @BeforeEach
    void reset() {
        payos.reset();
        ordering.reset();
        jdbc.update("TRUNCATE payment_intents, bank_webhook_log CASCADE");
    }

    private OpenIntentHandler.Result open(UUID orderId) {
        return openIntent.handle(new OpenIntentHandler.Command(
                orderId, UUID.randomUUID(), AMOUNT, Instant.now().plus(Duration.ofMinutes(15))));
    }

    // ------------------------------------------------------------------ mở link

    @Test
    @DisplayName("Mở intent: ghi đủ dữ liệu payOS, reference suy ra từ orderCode")
    void mo_intent_ghi_du_du_lieu_payos() {
        UUID orderId = UUID.randomUUID();

        OpenIntentHandler.Result result = open(orderId);

        assertThat(payos.created).hasSize(1);
        long orderCode = payos.created.get(0);

        // Sequence bắt đầu từ 1.000.000 để reference luôn đúng 9 ký tự — trần cứng của trường
        // description phía payOS. Một orderCode nhỏ hơn sinh chuỗi ngắn hơn và phá tính đồng nhất đó.
        assertThat(orderCode).isGreaterThanOrEqualTo(1_000_000L);
        assertThat(result.paymentReference()).isEqualTo("NT" + orderCode).hasSize(9);
        assertThat(result.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/link-" + orderCode);
        assertThat(result.vietQrPayload()).startsWith("000201");
        assertThat(result.bankAccountNumber()).isEqualTo("V3CAS" + orderCode);
        assertThat(result.bankAccountName()).isEqualTo("NEXATICKET");

        // Đọc lại từ database: nếu một tên cột trong SELECT sai thì hydrate đổ ở đây, không ở production.
        PaymentIntent stored = intents.findByOrder(orderId).orElseThrow();
        assertThat(stored.payosOrderCode()).isEqualTo(orderCode);
        assertThat(stored.payosPaymentLinkId()).isEqualTo("link-" + orderCode);
        assertThat(stored.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(intents.findByPayosOrderCode(orderCode)).isPresent();
    }

    @Test
    @DisplayName("Gọi lại openIntent KHÔNG tạo link payOS thứ hai")
    void open_intent_idempotent_khong_tao_link_thu_hai() {
        UUID orderId = UUID.randomUUID();

        OpenIntentHandler.Result first = open(orderId);
        OpenIntentHandler.Result second = open(orderId);

        // Saga gọi lại bước này sau timeout mạng. Tạo link mới nghĩa là khách đang nhìn một mã QR trỏ
        // về một tài khoản ảo mà hệ thống không còn công nhận — và tiền vào đó không khớp đơn nào.
        assertThat(payos.created).hasSize(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("payOS không phản hồi: 503 PAYOS_UNAVAILABLE, không để lại intent dở dang")
    void payos_khong_phan_hoi_thi_503() {
        UUID orderId = UUID.randomUUID();
        payos.unavailable = true;

        assertThatThrownBy(() -> open(orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("payOS unavailable");

        // Không có intent nào được ghi: một intent với mã QR rỗng là một đơn vĩnh viễn không trả được tiền.
        assertThat(intents.findByOrder(orderId)).isEmpty();
    }

    @Test
    @DisplayName("payOS từ chối tạo link: 502 PAYOS_REJECTED — thử lại sẽ nhận đúng câu đó")
    void payos_tu_choi_thi_502() {
        UUID orderId = UUID.randomUUID();
        payos.rejectCreate = true;

        assertThatThrownBy(() -> open(orderId)).isInstanceOf(ApiException.class).hasMessageContaining("payOS rejected");
    }

    @Test
    @DisplayName("payOS ghi số tiền lệch trên link: KHÔNG ghi intent nào")
    void so_tien_tren_link_lech_thi_khong_ghi_intent() {
        UUID orderId = UUID.randomUUID();
        payos.forcedAmountVnd = AMOUNT - 1;

        assertThatThrownBy(() -> open(orderId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(intents.findByOrder(orderId)).isEmpty();
    }

    // ------------------------------------------------------------------ webhook

    @Test
    @DisplayName("Webhook đã ký, đủ tiền: xác nhận, báo Ordering, ghi nhật ký CONFIRMED")
    void webhook_du_tien_thi_xac_nhan() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);

        HandlePayosWebhookHandler.Outcome outcome = webhook.handle(signedWebhook(orderCode, AMOUNT, txn("001")));

        assertThat(outcome.authentic()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("CONFIRMED");
        assertThat(ordering.confirmed).containsExactly(orderId);

        PaymentIntent stored = intents.findByOrder(orderId).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(stored.provider()).isEqualTo("PAYOS");
        assertThat(stored.providerTxnId()).isEqualTo(txn("001"));
        assertThat(stored.paidAmountVnd()).isEqualTo(AMOUNT);

        assertThat(logOutcomes(orderCode)).containsExactly("CONFIRMED");
    }

    @Test
    @DisplayName("Tiền vào một đơn đã đóng: vẫn 200 và vẫn ghi nhận, kèm ghi chú đối soát tay")
    void tien_vao_don_da_dong() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        ordering.orderAlreadyClosed = true;

        HandlePayosWebhookHandler.Outcome outcome = webhook.handle(signedWebhook(orderCode, AMOUNT, txn("007")));

        // 200, không 5xx. Trả lỗi ở đây khiến payOS giao lại mãi một message không bao giờ xử lý
        // được, và transaction rollback sẽ xoá luôn dòng nhật ký của một khoản tiền có thật.
        assertThat(outcome.authentic()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("CONFIRMED");
        assertThat(outcome.note()).contains("MANUAL_REVIEW");

        // Tiền có thật nên intent vẫn CONFIRMED, và dòng nhật ký ở lại để đối soát.
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(logOutcomes(orderCode)).containsExactly("CONFIRMED");
    }

    @Test
    @DisplayName("Job dọn: intent quá hạn thành EXPIRED, intent ĐÃ NHẬN TIỀN thì không đụng tới")
    void job_don_intent_qua_han() {
        UUID stale = UUID.randomUUID();
        UUID paid = UUID.randomUUID();
        long staleCode = payosOrderCodeOf(open(stale), stale);
        long paidCode = payosOrderCodeOf(open(paid), paid);
        webhook.handle(signedWebhook(paidCode, AMOUNT, txn("008")));

        // Chỉ đẩy hạn của HAI intent trong test này. Câu UPDATE không WHERE sẽ kéo theo mọi intent
        // mà các test trước để lại trong cùng database — và con số trả về thành vô nghĩa.
        jdbc.update(
                "UPDATE payment_intents SET expires_at = now() - interval '1 minute' WHERE order_id IN (?, ?)",
                stale,
                paid);
        assertThat(expireIntents.runOnce()).isEqualTo(1);

        assertThat(intents.findByOrder(stale).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
        // Điều kiện status = 'PENDING' nằm trong chính câu UPDATE. Không có nó, một khoản tiền
        // thật vừa xác nhận sẽ bị đánh dấu hết hạn và mất dấu vết.
        assertThat(intents.findByOrder(paid).orElseThrow().status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(staleCode).isNotEqualTo(paidCode);
    }

    @Test
    @DisplayName("Chữ ký SAI: trả 401 (không 200), ghi vết REJECTED, KHÔNG đổi trạng thái đơn")
    void chu_ky_sai_thi_401() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);

        String forged = signedWebhook(orderCode, AMOUNT, txn("666")).replace("\"signature\":\"", "\"signature\":\"00");

        HandlePayosWebhookHandler.Outcome outcome = webhook.handle(forged);

        // 401 là cố ý, và khác mọi nhánh từ chối khác. Trả 2xx cho một chữ ký sai nghĩa là hôm nào
        // checksum key bị cấu hình lệch, payOS sẽ thôi giao lại và MỌI khoản tiền vào im lặng biến mất.
        assertThat(outcome.authentic()).isFalse();
        assertThat(outcome.outcome()).isEqualTo("REJECTED");
        assertThat(ordering.confirmed).isEmpty();
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);

        // Ghi vết kể cả khi bị chặn ở cửa: một chữ ký sai chỉ nhìn ra được qua một chuỗi thời gian.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM bank_webhook_log WHERE outcome = 'REJECTED' AND note LIKE '%Chữ ký%'",
                        Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("Trả THIẾU tiền: AMOUNT_MISMATCH, không phát vé, vẫn nhận 200")
    void tra_thieu_tien_thi_khong_phat_ve() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);

        HandlePayosWebhookHandler.Outcome outcome = webhook.handle(signedWebhook(orderCode, 10_000L, txn("002")));

        assertThat(outcome.authentic()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(ordering.confirmed).isEmpty();
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Webhook trùng: lần hai là DUPLICATE, không báo Ordering thêm lần nữa")
    void webhook_trung_la_duplicate() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        String body = signedWebhook(orderCode, AMOUNT, txn("003"));

        webhook.handle(body);
        HandlePayosWebhookHandler.Outcome again = webhook.handle(body);

        // payOS giao lại cho tới khi nhận 2xx, nên đây là điều CHẮC CHẮN xảy ra, không phải trường hợp hiếm.
        assertThat(again.outcome()).isEqualTo("DUPLICATE");
        assertThat(ordering.confirmed).containsExactly(orderId);
    }

    @Test
    @DisplayName("orderCode không có intent nào (webhook thử của payOS): 200 UNKNOWN_REFERENCE")
    void order_code_la_cua_webhook_thu_thi_van_200() {
        // payOS gọi endpoint này với orderCode 123 ngay lúc đăng ký URL. Phải nhận 2xx, nếu không việc
        // đăng ký thất bại và sau đó KHÔNG có webhook thật nào tới cả.
        HandlePayosWebhookHandler.Outcome outcome = webhook.handle(signedWebhook(123L, 3000L, txn("PROBE")));

        assertThat(outcome.authentic()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("UNKNOWN_REFERENCE");
    }

    @Test
    @DisplayName("Ordering không phản hồi: ném ra để rollback và để payOS giao lại")
    void ordering_khong_phan_hoi_thi_rollback() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        ordering.unavailable = true;

        assertThatThrownBy(() -> webhook.handle(signedWebhook(orderCode, AMOUNT, txn("004"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ordering unavailable");

        // Transaction rollback: intent quay về PENDING. Một intent CONFIRMED mà Ordering không biết là
        // một khách đã trả tiền và vĩnh viễn không có vé — tệ hơn nhiều một vòng retry.
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(logOutcomes(orderCode)).isEmpty();

        // Và lần giao lại sau đó thành công.
        ordering.unavailable = false;
        assertThat(webhook.handle(signedWebhook(orderCode, AMOUNT, txn("004"))).outcome())
                .isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------ đối soát & huỷ

    @Test
    @DisplayName("Đối soát kéo trạng thái PAID từ payOS về — đường sửa khi webhook không tới")
    void doi_soat_keo_trang_thai_ve() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        payos.settlements.put(orderCode, new PayosGateway.Settlement("PAID", AMOUNT, txn("100")));

        ConfirmTransferHandler.Result result = reconcileIntent.handle(orderId);

        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        assertThat(ordering.confirmed).containsExactly(orderId);

        // Gọi lại an toàn: lần hai chỉ trả DUPLICATE, không phát vé lần nữa.
        assertThat(reconcileIntent.handle(orderId).outcome()).isEqualTo("DUPLICATE");
        assertThat(ordering.confirmed).containsExactly(orderId);
    }

    @Test
    @DisplayName("Đối soát khi payOS nói PAID mà không có mã giao dịch: KHÔNG bịa mã thay thế")
    void doi_soat_thieu_ma_giao_dich_thi_khong_xac_nhan() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        payos.settlements.put(orderCode, new PayosGateway.Settlement("PAID", AMOUNT, null));

        ConfirmTransferHandler.Result result = reconcileIntent.handle(orderId);

        // Mã giao dịch là khoá chống ghi nhận trùng. Một khoá bịa sẽ khiến lần chuyển tiền THẬT sau đó
        // bị coi là trùng lặp — tức là một khoản tiền thứ hai biến mất không dấu vết.
        assertThat(result.outcome()).isEqualTo("NOT_PAID");
        assertThat(ordering.confirmed).isEmpty();
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Huỷ intent cũng đóng link ở phía payOS")
    void huy_intent_thi_dong_ca_link_payos() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);

        assertThat(cancelIntent.handle(orderId)).isTrue();

        // Bỏ bước này nghĩa là một đơn đã huỷ vẫn để lại một tài khoản ảo sống: khách mở lại tab cũ,
        // chuyển tiền, và tiền vào một đơn không còn ghế.
        assertThat(payos.cancelled).containsExactly(orderCode);
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.CANCELLED);

        // Bù trừ chạy lại nhiều lần: lần hai không làm gì và KHÔNG gọi payOS thêm.
        assertThat(cancelIntent.handle(orderId)).isFalse();
        assertThat(payos.cancelled).containsExactly(orderCode);
    }

    @Test
    @DisplayName("Đã nhận tiền thì không huỷ, và không đóng link")
    void da_nhan_tien_thi_khong_huy() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        webhook.handle(signedWebhook(orderCode, AMOUNT, txn("005")));

        assertThat(cancelIntent.handle(orderId)).isFalse();
        assertThat(payos.cancelled).isEmpty();
        assertThat(intents.findByOrder(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Intent đã huỷ: tiền vào sau đó KHÔNG được ghi nhận tự động")
    void huy_roi_thi_tien_vao_sau_khong_tu_ghi_nhan() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);
        cancelIntent.handle(orderId);

        HandlePayosWebhookHandler.Outcome outcome = webhook.handle(signedWebhook(orderCode, AMOUNT, txn("006")));

        assertThat(outcome.outcome()).isEqualTo("NOT_PENDING");
        assertThat(ordering.confirmed).isEmpty();
    }

    // ------------------------------------------------------------------ sandbox

    @Test
    @DisplayName("Giả lập chuyển khoản đi qua đúng cùng đường với webhook thật")
    void sandbox_di_qua_cung_duong() {
        UUID orderId = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(orderId), orderId);

        ConfirmTransferHandler.Result result =
                simulateTransfer.handle(new SimulateTransferHandler.Command(orderId, null, txn("SBX")));

        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        assertThat(ordering.confirmed).containsExactly(orderId);

        // Provider là SANDBOX, không PAYOS: nhật ký phải nói rõ đây là tiền GIẢ. Lẫn hai cái với nhau là
        // mất khả năng trả lời câu hỏi "đơn này đã có tiền thật vào chưa".
        assertThat(intents.findByOrder(orderId).orElseThrow().provider()).isEqualTo("SANDBOX");
        assertThat(logOutcomes(orderCode)).containsExactly("CONFIRMED");
    }

    @Test
    @DisplayName("Giả lập trả thiếu tiền vẫn bị chặn — cùng một nhánh domain")
    void sandbox_tra_thieu_van_bi_chan() {
        UUID orderId = UUID.randomUUID();
        open(orderId);

        ConfirmTransferHandler.Result result =
                simulateTransfer.handle(new SimulateTransferHandler.Command(orderId, 10_000L, null));

        assertThat(result.outcome()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(ordering.confirmed).isEmpty();
    }

    // ------------------------------------------------------------------ schema

    @Test
    @DisplayName("Sequence orderCode không cấp trùng, kể cả khi gọi song song")
    void sequence_order_code_khong_trung() {
        List<Long> codes = java.util.stream.IntStream.range(0, 50)
                .parallel()
                .mapToObj(i -> intents.nextOrderCode())
                .toList();

        // payOS TỪ CHỐI một orderCode đã dùng, nên đây là ràng buộc cứng, không phải chuyện gọn gàng.
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(code).isBetween(1_000_000L, 9_999_999L));
    }

    @Test
    @DisplayName("uq_payos_order_code chặn hai intent cùng một mã link")
    void khong_hai_intent_cung_order_code() {
        UUID first = UUID.randomUUID();
        long orderCode = payosOrderCodeOf(open(first), first);

        // Hai intent cùng orderCode nghĩa là một webhook "đã trả tiền" không biết thuộc đơn nào, và cả
        // hai đơn đều có thể được phát vé từ một lần chuyển tiền. Chốt chặn phải ở database.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO payment_intents (order_id, organization_id, reference, amount_vnd, status,
                            vietqr_payload, bank_bin, bank_account_number, payos_order_code, expires_at)
                        VALUES (?, ?, ?, ?, 'PENDING', 'x', '970422', '1', ?, now())
                        """,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        // reference là UNIQUE và container được dùng lại giữa các lần build: một hằng số
                        // ở đây sẽ vỡ vì trùng reference, không vì trùng orderCode — tức là test xanh/đỏ
                        // vì một ràng buộc khác với ràng buộc nó muốn kiểm.
                        "NT" + intents.nextOrderCode(),
                        AMOUNT,
                        orderCode))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ helpers

    private long payosOrderCodeOf(OpenIntentHandler.Result result, UUID orderId) {
        assertThat(result.paymentReference()).isNotNull();
        return intents.findByOrder(orderId).orElseThrow().payosOrderCode();
    }

    private List<String> logOutcomes(long orderCode) {
        return jdbc.queryForList(
                "SELECT outcome FROM bank_webhook_log WHERE payos_order_code = ? ORDER BY received_at",
                String.class,
                orderCode);
    }

    /**
     * Dựng một webhook payOS <b>đã ký thật</b>.
     *
     * <p>Ký bằng đúng {@code PayosSignature} và đúng checksum key của test, chứ không gắn một chuỗi bất kỳ:
     * nếu phép kiểm chữ ký bị hỏng, những test này phải đỏ, không phải xanh.
     */
    private String signedWebhook(long orderCode, long amountVnd, String reference) {
        Map<String, Object> data = new TreeMap<>();
        data.put("orderCode", orderCode);
        data.put("amount", amountVnd);
        data.put("description", "NT" + orderCode);
        data.put("accountNumber", "V3CAS" + orderCode);
        data.put("reference", reference);
        data.put("transactionDateTime", "2026-09-09 10:25:00");
        data.put("currency", "VND");
        data.put("paymentLinkId", "link-" + orderCode);
        data.put("code", "00");
        data.put("desc", "Thanh cong");

        com.fasterxml.jackson.databind.node.ObjectNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        data.forEach((key, value) -> {
            if (value instanceof Long number) {
                node.put(key, number);
            } else {
                node.put(key, (String) value);
            }
        });

        String signature = PayosSignature.sign(node, PaymentTestBase.CHECKSUM_KEY);
        return "{\"code\":\"00\",\"desc\":\"success\",\"success\":true,\"data\":%s,\"signature\":\"%s\"}"
                .formatted(node.toString(), signature);
    }
}
