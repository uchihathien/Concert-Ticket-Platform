// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.payment.application.command.CancelIntentHandler;
import com.nexaticket.payment.application.command.ConfirmTransferHandler;
import com.nexaticket.payment.application.command.ReconcileIntentHandler;
import com.nexaticket.payment.support.FakePayos;
import com.nexaticket.payment.support.PaymentTestBase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Intent mở từ trước khi hệ thống dùng payOS.
 *
 * <h2>Vì sao những dòng này tồn tại</h2>
 *
 * <p>{@code payos_order_code} được thêm vào một bảng <b>đã có dữ liệu</b> ở V0101, nên mọi intent
 * thời SePay mang NULL ở cột ấy — và unique index là partial ({@code WHERE ... IS NOT NULL}) đúng
 * vì thế.
 *
 * <h2>Vì sao phải có test chạm database thật</h2>
 *
 * <p>Lỗi nằm ở chỗ nối giữa JDBC và miền, không nằm trong logic nào: {@code rs.getLong} trả
 * {@code 0} cho cột NULL và không báo gì, nên intent cũ đọc lên mang mã đơn 0 như thể đó là mã
 * thật. Rồi đối soát nó sẽ gọi {@code payos.fetchSettlement(0)} — một request thật ra nhà cung cấp
 * thanh toán với một mã bịa. Dựng {@code PaymentIntent} bằng tay trong unit test thì không bao giờ
 * đi qua chỗ nối ấy, nên chỉ có hàng NULL thật trong database mới lộ ra.
 */
class LegacyIntentWithoutPayosIT extends PaymentTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ReconcileIntentHandler reconcile;

    @Autowired
    private CancelIntentHandler cancel;

    @Autowired
    private FakePayos.FakePayosGateway payos;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM payment_intents").update();
        payos.reset();
    }

    @Test
    @DisplayName("đối soát intent cũ KHÔNG gọi payOS với mã đơn 0")
    void doi_soat_khong_goi_payos() {
        UUID orderId = legacyIntent("PENDING");

        ConfirmTransferHandler.Result result = reconcile.handle(orderId);

        // Điều quan trọng là danh sách này rỗng. Một lời gọi với mã 0 sẽ nhận về "không biết link
        // này" — payOS nói đúng, nhưng câu ấy đổ lỗi cho payOS thay vì nói ra tuổi của dữ liệu.
        assertThat(payos.fetched).isEmpty();
        assertThat(result.outcome()).isEqualTo("UNKNOWN_REFERENCE");
        assertThat(result.note()).contains("trước khi dùng payOS");
    }

    @Test
    @DisplayName("huỷ intent cũ KHÔNG gọi payOS đóng link")
    void huy_khong_goi_payos() {
        UUID orderId = legacyIntent("PENDING");

        cancel.handle(orderId);

        // Không có link thì không có gì để đóng.
        assertThat(payos.cancelled).isEmpty();
    }

    /** Một hàng đúng hình dạng thời SePay: có đủ mọi thứ TRỪ phần payOS. */
    private UUID legacyIntent(String status) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql(
                        """
                        INSERT INTO payment_intents
                            (order_id, organization_id, reference, amount_vnd, vietqr_payload,
                             bank_bin, bank_account_number, expires_at, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(
                        orderId,
                        UUID.randomUUID(),
                        // Khuôn thật: "NT" + đúng 7 chữ số (xem PaymentReference).
                        "NT%07d".formatted(System.nanoTime() % 10_000_000),
                        500_000L,
                        "00020101021238570010A00000072701270006970422",
                        "970422",
                        "0123456789",
                        OffsetDateTime.now().plusHours(1),
                        status)
                .update();
        return orderId;
    }
}
