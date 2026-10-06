// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentIntent.Settlement;
import com.nexaticket.payment.domain.model.PaymentReference;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.model.PayosLink;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Đối chiếu tiền — nơi một nhánh thiếu biến thành mất tiền thật. */
class PaymentIntentTest {

    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");
    private static final long AMOUNT = 3_000_000L;
    private static final long ORDER_CODE = 1_000_001L;
    private static final String PROVIDER = "PAYOS";

    private static PayosLink link(long amountVnd) {
        return new PayosLink(
                ORDER_CODE,
                "124c33293c43417ab7879e14c8d9eb18",
                "https://pay.payos.vn/web/124c33293c43417ab7879e14c8d9eb18",
                "00020101021238570010A00000072701270006970422011312345678901230208QRIBFTTA53037045802VN6304AB12",
                "970422",
                "V3CAS0123456789",
                "NEXATICKET",
                amountVnd,
                "PENDING");
    }

    private PaymentIntent intent() {
        return PaymentIntent.open(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AMOUNT,
                NOW.plus(Duration.ofMinutes(15)),
                PaymentReference.forOrderCode(ORDER_CODE),
                link(AMOUNT));
    }

    @Test
    @DisplayName("Mở intent: giữ nguyên mã QR, tài khoản ảo và link thanh toán payOS trả về")
    void mo_intent_giu_nguyen_du_lieu_payos() {
        PaymentIntent intent = intent();

        // Lưu lại chứ không dựng lại: tài khoản ảo gắn với một link là cố định, và mã khách đã chụp màn
        // hình phải quét ra đúng tài khoản cũ kể cả sau khi cấu hình kênh đã đổi.
        assertThat(intent.payosOrderCode()).isEqualTo(ORDER_CODE);
        assertThat(intent.reference().value()).isEqualTo("NT1000001");
        assertThat(intent.vietQrPayload()).isEqualTo(link(AMOUNT).qrCode());
        assertThat(intent.bankAccountNumber()).isEqualTo("V3CAS0123456789");
        assertThat(intent.checkoutUrl()).startsWith("https://pay.payos.vn/");
        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("payOS ghi số tiền khác đơn: KHÔNG mở intent")
    void so_tien_tren_link_lech_thi_khong_mo() {
        // Lệch nghĩa là mã QR khách quét ra sai số tiền. Chặn trước khi nó vào database, vì sau đó thì đã
        // có một chuỗi QR sai nằm trong đơn và khách có thể đã chụp màn hình.
        assertThatThrownBy(() -> PaymentIntent.open(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AMOUNT,
                        NOW.plus(Duration.ofMinutes(15)),
                        PaymentReference.forOrderCode(ORDER_CODE),
                        link(AMOUNT - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trên link nhưng đơn cần");
    }

    @Test
    @DisplayName("Reference không thuộc orderCode của link: KHÔNG mở intent")
    void reference_lech_order_code_thi_khong_mo() {
        // Lệch ở đây nghĩa là nội dung chuyển khoản khách thấy thuộc một link khác. Webhook vẫn khớp theo
        // orderCode nên tiền vẫn về đúng đơn, nhưng mọi cuộc đối soát tay sau đó sẽ dò theo một mã không
        // tồn tại.
        assertThatThrownBy(() -> PaymentIntent.open(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AMOUNT,
                        NOW.plus(Duration.ofMinutes(15)),
                        PaymentReference.forOrderCode(ORDER_CODE + 1),
                        link(AMOUNT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không thuộc orderCode");
    }

    @Test
    @DisplayName("Chuyển đủ tiền: ghi nhận và chốt trạng thái")
    void tra_du_thi_ghi_nhan() {
        PaymentIntent intent = intent();

        assertThat(intent.confirm(PROVIDER, "TF230204212323", AMOUNT, NOW)).isEqualTo(Settlement.CONFIRMED);
        assertThat(intent.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(intent.confirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Chuyển THIẾU tiền thì KHÔNG được ghi nhận")
    void tra_thieu_thi_tu_choi() {
        PaymentIntent intent = intent();

        // Không có nhánh này thì một khách chuyển 10.000đ cho đơn 3.000.000đ vẫn được phát vé thật.
        assertThat(intent.confirm(PROVIDER, "TF1", 10_000L, NOW)).isEqualTo(Settlement.AMOUNT_MISMATCH);
        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Chuyển THỪA tiền vẫn ghi nhận; phần dư hoàn ngoài hệ thống")
    void tra_thua_van_ghi_nhan() {
        PaymentIntent intent = intent();

        // Chặn ở đây sẽ để một khách đã trả đủ tiền không có vé — tệ hơn nhiều một khoản hoàn tay.
        assertThat(intent.confirm(PROVIDER, "TF1", AMOUNT + 50_000L, NOW)).isEqualTo(Settlement.CONFIRMED);
        assertThat(intent.paidAmountVnd()).isEqualTo(AMOUNT + 50_000L);
    }

    @Test
    @DisplayName("Cùng một giao dịch tới lần hai là trùng, không phải lỗi")
    void webhook_trung_la_binh_thuong() {
        PaymentIntent intent = intent();
        intent.confirm(PROVIDER, "TF1", AMOUNT, NOW);

        // Mọi cổng thanh toán đều giao lại cho tới khi nhận 2xx, nên đây là điều CHẮC CHẮN xảy ra.
        assertThat(intent.confirm(PROVIDER, "TF1", AMOUNT, NOW.plusSeconds(30))).isEqualTo(Settlement.DUPLICATE);
        assertThat(intent.confirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Giao dịch KHÁC trên đơn đã trả tiền: cần người đối soát, không tự xử")
    void chuyen_tien_hai_lan_thi_bao_dong() {
        PaymentIntent intent = intent();
        intent.confirm(PROVIDER, "TF1", AMOUNT, NOW);

        assertThat(intent.confirm(PROVIDER, "TF2", AMOUNT, NOW.plusSeconds(60)))
                .isEqualTo(Settlement.ALREADY_CONFIRMED);
    }

    @Test
    @DisplayName("Đã nhận tiền thì không huỷ được yêu cầu thanh toán")
    void da_nhan_tien_thi_khong_huy() {
        PaymentIntent intent = intent();
        intent.confirm(PROVIDER, "TF1", AMOUNT, NOW);

        // Huỷ một yêu cầu đã có tiền vào là xoá dấu vết của một khoản tiền có thật.
        assertThat(intent.cancel(NOW.plusSeconds(1))).isFalse();
        assertThat(intent.status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Yêu cầu đã huỷ thì tiền vào sau đó không được ghi nhận tự động")
    void huy_roi_thi_khong_ghi_nhan() {
        PaymentIntent intent = intent();
        assertThat(intent.cancel(NOW)).isTrue();

        assertThat(intent.confirm(PROVIDER, "TF1", AMOUNT, NOW.plusSeconds(5))).isEqualTo(Settlement.NOT_PENDING);
    }

    @Test
    @DisplayName("Reference và orderCode là hai cách viết của cùng một số")
    void reference_va_order_code_la_mot() {
        // Quan hệ này là thứ cho phép một con người nhìn màn hình CSKH, sao kê ngân hàng và log payOS mà
        // thấy cùng một con số.
        PaymentReference reference = PaymentReference.forOrderCode(1_234_567L);

        assertThat(reference.value()).isEqualTo("NT1234567");
        assertThat(reference.orderCode()).isEqualTo(1_234_567L);
        // Đúng 9 ký tự — trần cứng của trường description phía payOS.
        assertThat(reference.value()).hasSize(PaymentReference.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    @DisplayName("orderCode vượt dải thì NÉM LỖI, không lặng lẽ sinh chuỗi 10 ký tự")
    void order_code_vuot_dai_thi_nem_loi() {
        // Một description 10 ký tự làm payOS từ chối MỌI lần tạo link sau đó, tức là checkout chết hoàn
        // toàn. Đó là thứ phải nổ to ở đúng chỗ, không phải một lỗi chung chung từ phía họ.
        assertThatThrownBy(() -> PaymentReference.forOrderCode(PaymentReference.MAX_ORDER_CODE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nằm ngoài khoảng");

        assertThatThrownBy(() -> PaymentReference.forOrderCode(PaymentReference.MIN_ORDER_CODE - 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
