// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.model.PaymentIntent.Settlement;
import com.nexaticket.payment.domain.model.PaymentReference;
import com.nexaticket.payment.domain.model.PaymentStatus;
import com.nexaticket.payment.domain.model.PayosLink;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ma trận trạng thái của yêu cầu thanh toán, và ràng buộc của hai value object quanh nó.
 *
 * <p>Bổ sung cho {@code PaymentIntentTest}: lớp đó canh các nhánh chính của {@code confirm}; lớp này canh
 * các ô còn lại của ma trận (trạng thái EXPIRED, huỷ hai lần, dữ liệu rehydrate) và các đầu vào biên mà
 * payOS hoặc database có thể trả về.
 */
class PaymentStateMatrixTest {

    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");
    private static final long AMOUNT = 500_000L;
    private static final long CODE = 1_234_567L;

    private static PayosLink link(long orderCode, long amount) {
        return new PayosLink(
                orderCode, "lnk", "https://pay.payos.vn/web/lnk", "000201qr", "970422", "V3CAS1", "NT", amount, null);
    }

    private static PaymentIntent rehydrated(PaymentStatus status, String providerTxnId) {
        return PaymentIntent.rehydrate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "NT" + CODE,
                AMOUNT,
                "000201qr",
                "970422",
                "V3CAS1",
                "NT",
                CODE,
                "lnk",
                "https://pay.payos.vn/web/lnk",
                NOW.plusSeconds(900),
                status,
                providerTxnId == null ? null : "PAYOS",
                providerTxnId,
                providerTxnId == null ? null : AMOUNT,
                providerTxnId == null ? null : NOW,
                null);
    }

    @Nested
    @DisplayName("Ma trận confirm theo trạng thái")
    class MaTranConfirm {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = PaymentStatus.class,
                names = {"CANCELLED", "EXPIRED"})
        @DisplayName("Intent đã đóng: NOT_PENDING và KHÔNG đổi gì trên aggregate")
        void da_dong_thi_not_pending(PaymentStatus status) {
            PaymentIntent intent = rehydrated(status, null);

            assertThat(intent.confirm("PAYOS", "TF1", AMOUNT, NOW)).isEqualTo(Settlement.NOT_PENDING);

            assertThat(intent.status()).isEqualTo(status);
            assertThat(intent.providerTxnId()).isNull();
            assertThat(intent.paidAmountVnd()).isNull();
            assertThat(intent.confirmedAt()).isNull();
        }

        @Test
        @DisplayName("Đã đóng VÀ trả thiếu: NOT_PENDING thắng — trạng thái kiểm trước số tiền")
        void da_dong_va_tra_thieu() {
            assertThat(rehydrated(PaymentStatus.EXPIRED, null).confirm("PAYOS", "TF1", 1L, NOW))
                    .isEqualTo(Settlement.NOT_PENDING);
        }

        @Test
        @DisplayName("Đã xác nhận rồi, giao dịch khác trả THIẾU: ALREADY_CONFIRMED, không ghi đè số đã trả")
        void da_xac_nhan_giao_dich_khac_tra_thieu() {
            PaymentIntent intent = rehydrated(PaymentStatus.CONFIRMED, "TF1");

            assertThat(intent.confirm("PAYOS", "TF2", 1L, NOW.plusSeconds(5))).isEqualTo(Settlement.ALREADY_CONFIRMED);
            assertThat(intent.providerTxnId()).isEqualTo("TF1");
            assertThat(intent.paidAmountVnd()).isEqualTo(AMOUNT);
            assertThat(intent.confirmedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Trả thiếu không để lại dấu nửa vời trên aggregate")
        void tra_thieu_khong_de_dau() {
            PaymentIntent intent = rehydrated(PaymentStatus.PENDING, null);

            assertThat(intent.confirm("PAYOS", "TF1", AMOUNT - 1, NOW)).isEqualTo(Settlement.AMOUNT_MISMATCH);

            assertThat(intent.providerTxnId()).isNull();
            assertThat(intent.provider()).isNull();
            assertThat(intent.paidAmountVnd()).isNull();
            // Tiền bù sau đó bằng một giao dịch đủ vẫn phải ghi nhận được.
            assertThat(intent.confirm("PAYOS", "TF2", AMOUNT, NOW)).isEqualTo(Settlement.CONFIRMED);
            assertThat(intent.providerTxnId()).isEqualTo("TF2");
        }

        @Test
        @DisplayName("Trả đúng bằng số của đơn là đủ — biên không lệch một đồng")
        void tra_dung_bien() {
            PaymentIntent intent = rehydrated(PaymentStatus.PENDING, null);

            assertThat(intent.confirm("SANDBOX", "SBX-1", AMOUNT, NOW)).isEqualTo(Settlement.CONFIRMED);
            assertThat(intent.provider()).isEqualTo("SANDBOX");
        }
    }

    @Nested
    @DisplayName("Ma trận cancel")
    class MaTranCancel {

        @Test
        @DisplayName("Huỷ lần hai: false và giữ mốc huỷ của lần đầu")
        void huy_hai_lan() {
            PaymentIntent intent = rehydrated(PaymentStatus.PENDING, null);
            assertThat(intent.cancel(NOW)).isTrue();

            assertThat(intent.cancel(NOW.plusSeconds(60))).isFalse();
            assertThat(intent.cancelledAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Intent đã hết hạn: không huỷ được, giữ EXPIRED")
        void het_han_khong_huy() {
            PaymentIntent intent = rehydrated(PaymentStatus.EXPIRED, null);

            assertThat(intent.cancel(NOW)).isFalse();
            assertThat(intent.status()).isEqualTo(PaymentStatus.EXPIRED);
            assertThat(intent.isPending()).isFalse();
            assertThat(intent.isConfirmed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Dựng aggregate")
    class DungAggregate {

        @ParameterizedTest(name = "amount={0}")
        @ValueSource(longs = {0L, -1L})
        @DisplayName("Số tiền không dương: không dựng được, kể cả khi rehydrate từ database")
        void so_tien_khong_duong(long amount) {
            assertThatThrownBy(() -> PaymentIntent.rehydrate(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "NT" + CODE,
                            amount,
                            null,
                            null,
                            null,
                            null,
                            CODE,
                            null,
                            null,
                            NOW,
                            PaymentStatus.PENDING,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dương");
        }

        @Test
        @DisplayName("Reference sai khuôn trong database: không rehydrate được")
        void reference_sai_khuon() {
            assertThatThrownBy(() -> PaymentIntent.rehydrate(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "ABCDEFGHJK",
                            AMOUNT,
                            null,
                            null,
                            null,
                            null,
                            CODE,
                            null,
                            null,
                            NOW,
                            PaymentStatus.PENDING,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Intent không có link payOS (mã 0) và intent có link phân biệt được")
        void co_hay_khong_co_link() {
            PaymentIntent legacy = PaymentIntent.rehydrate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "NT0000001",
                    AMOUNT,
                    null,
                    null,
                    null,
                    null,
                    PaymentIntent.NO_PAYOS_LINK,
                    null,
                    null,
                    NOW,
                    PaymentStatus.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThat(legacy.hasPayosLink()).isFalse();
            assertThat(rehydrated(PaymentStatus.PENDING, null).hasPayosLink()).isTrue();
        }
    }

    @Nested
    @DisplayName("PaymentReference")
    class Reference {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "NT123456", "NT12345678", "nt1234567", "NT123456A", "XX1234567", " NT1234567"})
        @DisplayName("Sai khuôn NT + 7 chữ số: từ chối")
        void sai_khuon(String value) {
            assertThatThrownBy(() -> new PaymentReference(value)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null: từ chối")
        void null_bi_tu_choi() {
            assertThatThrownBy(() -> new PaymentReference(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Hai đầu của dải orderCode đều sinh đúng 9 ký tự và đảo ngược được")
        void hai_dau_dai() {
            PaymentReference min = PaymentReference.forOrderCode(PaymentReference.MIN_ORDER_CODE);
            PaymentReference max = PaymentReference.forOrderCode(PaymentReference.MAX_ORDER_CODE);

            assertThat(min.value()).isEqualTo("NT1000000");
            assertThat(max.value()).isEqualTo("NT9999999");
            assertThat(min.orderCode()).isEqualTo(PaymentReference.MIN_ORDER_CODE);
            assertThat(max.orderCode()).isEqualTo(PaymentReference.MAX_ORDER_CODE);
            assertThat(max.toString()).isEqualTo("NT9999999");
        }
    }

    @Nested
    @DisplayName("PayosLink")
    class Link {

        @Test
        @DisplayName("Link hợp lệ giữ nguyên dữ liệu payOS trả về; status và accountName được phép trống")
        void link_hop_le() {
            PayosLink link = link(CODE, AMOUNT);

            assertThat(link.orderCode()).isEqualTo(CODE);
            assertThat(link.status()).isNull();
        }

        @ParameterizedTest(name = "trường {0}")
        @ValueSource(strings = {"paymentLinkId", "checkoutUrl", "qrCode", "bin", "accountNumber"})
        @DisplayName("Thiếu trường bắt buộc (trắng): từ chối — không để một intent không quét được vào database")
        void thieu_truong_bat_buoc(String field) {
            assertThatThrownBy(() -> new PayosLink(
                            CODE,
                            "paymentLinkId".equals(field) ? " " : "lnk",
                            "checkoutUrl".equals(field) ? "" : "https://pay",
                            "qrCode".equals(field) ? null : "qr",
                            "bin".equals(field) ? "" : "970422",
                            "accountNumber".equals(field) ? null : "V3",
                            null,
                            AMOUNT,
                            "PENDING"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "orderCode={0}")
        @ValueSource(longs = {0L, -5L})
        @DisplayName("orderCode không dương: từ chối")
        void order_code_khong_duong(long orderCode) {
            assertThatThrownBy(() -> link(orderCode, AMOUNT)).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "amount={0}")
        @ValueSource(longs = {0L, -1L})
        @DisplayName("Số tiền không dương: từ chối")
        void so_tien_khong_duong(long amount) {
            assertThatThrownBy(() -> link(CODE, amount)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
