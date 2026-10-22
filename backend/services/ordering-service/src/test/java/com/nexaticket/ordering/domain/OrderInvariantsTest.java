// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain;

import static com.nexaticket.ordering.support.OrderFixtures.NOW;
import static com.nexaticket.ordering.support.OrderFixtures.WINDOW;
import static com.nexaticket.ordering.support.OrderFixtures.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderItem;
import com.nexaticket.ordering.domain.model.OrderNumber;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.model.SagaStatus;
import com.nexaticket.ordering.support.OrderFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Bất biến của Order, OrderItem, OrderNumber và CheckoutSaga mà OrderTest chưa chạm tới. */
class OrderInvariantsTest {

    @Nested
    @DisplayName("Order")
    class Aggregate {

        @Test
        @DisplayName("Đơn không có dòng nào bị từ chối ngay khi dựng")
        void don_rong_bi_tu_choi() {
            assertThatThrownBy(() -> Order.awaitingPayment(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            List.of(),
                            null,
                            NOW,
                            WINDOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Danh sách dòng là bản sao bất biến — sửa list gốc không đổi được tổng tiền")
        void dong_don_la_ban_sao_bat_bien() {
            List<OrderItem> items = new ArrayList<>(List.of(item(100_000, 0, 500)));
            Order order = Order.awaitingPayment(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    items,
                    null,
                    NOW,
                    WINDOW);

            items.add(item(900_000, 0, 500));

            assertThat(order.total()).isEqualTo(Money.ofVnd(100_000));
            assertThatThrownBy(() -> order.items().add(item(1, 0, 0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Tạm tính, giảm giá, thành tiền đều cộng từ các dòng")
        void tong_tien_cong_tu_dong() {
            Order order = OrderFixtures.inStatus(OrderStatus.AWAITING_PAYMENT, UUID.randomUUID());

            assertThat(order.subtotal().amountVnd()).isEqualTo(800_000);
            assertThat(order.discount().amountVnd()).isEqualTo(100_000);
            assertThat(order.total().amountVnd()).isEqualTo(700_000);
            // 5% của (400.000) + 5% của (300.000)
            assertThat(order.commission().amountVnd()).isEqualTo(35_000);
            assertThat(order.commissionBps()).isEqualTo(500);
        }

        @Test
        @DisplayName("Đơn mới: hạn thanh toán = lúc đặt + cửa sổ, mã đơn mang ngày đặt")
        void don_moi_co_han_va_ma() {
            Order order = OrderFixtures.awaiting(UUID.randomUUID());

            assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            assertThat(order.paymentExpiresAt()).isEqualTo(NOW.plus(WINDOW));
            assertThat(order.orderNumber().value()).startsWith("NT-260908-");
            assertThat(order.paidAt()).isNull();
            assertThat(order.paymentReference()).isNull();
        }

        @Test
        @DisplayName("Chủ đơn là đúng người đặt; người khác hay null đều không phải")
        void chu_don() {
            UUID owner = UUID.randomUUID();
            Order order = OrderFixtures.awaiting(owner);

            assertThat(order.isOwnedBy(owner)).isTrue();
            assertThat(order.isOwnedBy(UUID.randomUUID())).isFalse();
            assertThat(order.isOwnedBy(null)).isFalse();
        }

        @Test
        @DisplayName("Gắn thanh toán lưu nguyên snapshot mã QR và link payOS")
        void gan_thanh_toan_luu_snapshot() {
            Order order = OrderFixtures.awaiting(UUID.randomUUID());

            order.attachPayment("NT1234567", "0002010102...", "https://pay.payos.vn/web/1234567");

            assertThat(order.paymentReference()).isEqualTo("NT1234567");
            assertThat(order.vietQrPayload()).isEqualTo("0002010102...");
            assertThat(order.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/1234567");
            assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }
    }

    @Nested
    @DisplayName("OrderItem")
    class Item {

        @ParameterizedTest(name = "{0} bps")
        @ValueSource(ints = {-1, 10_001, Integer.MIN_VALUE})
        @DisplayName("Hoa hồng ngoài khoảng 0..10000 bị từ chối")
        void hoa_hong_ngoai_khoang(int bps) {
            assertThatThrownBy(() -> item(100_000, 0, bps)).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0} bps")
        @ValueSource(ints = {0, 10_000})
        @DisplayName("Hai biên 0% và 100% hợp lệ")
        void bien_hoa_hong_hop_le(int bps) {
            assertThatCode(() -> item(100_000, 0, bps)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Giảm đúng bằng đơn giá là hợp lệ: vé 0đ, hoa hồng 0đ")
        void giam_bang_don_gia() {
            OrderItem free = item(200_000, 200_000, 1_000);

            assertThat(free.netPrice()).isEqualTo(Money.ZERO);
            assertThat(free.commission()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("Hoa hồng của dòng làm tròn xuống, không bao giờ lên")
        void hoa_hong_lam_tron_xuong() {
            // 5% của 19.999 = 999,95 ⇒ 999
            assertThat(item(19_999, 0, 500).commission().amountVnd()).isEqualTo(999);
        }
    }

    @Nested
    @DisplayName("OrderNumber")
    class Number {

        @Test
        @DisplayName("Định dạng NT-yyMMdd-XXXXXX với 6 ký tự trong bảng Crockford bỏ I/L/O/U")
        void dinh_dang() {
            for (int i = 0; i < 100; i++) {
                assertThat(OrderNumber.generate(NOW).value()).matches("NT-260908-[0-9A-HJKMNP-TV-Z]{6}");
            }
        }

        @Test
        @DisplayName("Ngày trong mã theo giờ Việt Nam: 23:30 UTC đã là ngày hôm sau ở Hà Nội")
        void ngay_theo_gio_viet_nam() {
            Instant lateUtc = Instant.parse("2026-09-07T23:30:00Z"); // 06:30 ngày 08/09 giờ VN

            assertThat(OrderNumber.generate(lateUtc).value()).startsWith("NT-260908-");
        }

        @Test
        @DisplayName("Ngay trước nửa đêm giờ Việt Nam vẫn là ngày cũ")
        void truoc_nua_dem_viet_nam() {
            Instant justBefore = Instant.parse("2026-09-07T16:59:59Z"); // 23:59:59 ngày 07/09 giờ VN

            assertThat(OrderNumber.generate(justBefore).value()).startsWith("NT-260907-");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("Mã rỗng hoặc toàn khoảng trắng bị từ chối")
        void ma_rong(String value) {
            assertThatThrownBy(() -> new OrderNumber(value)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Mã null bị từ chối")
        void ma_null() {
            assertThatThrownBy(() -> new OrderNumber(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("toString trả đúng giá trị — dùng trong log và nội dung chuyển khoản")
        void to_string() {
            assertThat(new OrderNumber("NT-260908-K7M2QP")).hasToString("NT-260908-K7M2QP");
        }
    }

    @Nested
    @DisplayName("CheckoutSaga")
    class Saga {

        private final UUID orderId = UUID.randomUUID();

        @Test
        @DisplayName("Saga mới: STARTED, chưa có gì để bù trừ")
        void saga_moi() {
            CheckoutSaga saga = CheckoutSaga.start(orderId, UUID.randomUUID(), UUID.randomUUID());

            assertThat(saga.status()).isEqualTo(SagaStatus.STARTED);
            assertThat(saga.hasSomethingToCompensate()).isFalse();
            assertThat(saga.attempts()).isZero();
        }

        @Test
        @DisplayName("Đã đặt chỗ hoặc đã mở intent thì có thứ để bù trừ")
        void co_thu_de_bu_tru() {
            CheckoutSaga seats = CheckoutSaga.start(orderId, UUID.randomUUID(), UUID.randomUUID());
            seats.seatsReserved();
            CheckoutSaga intent = CheckoutSaga.start(orderId, UUID.randomUUID(), UUID.randomUUID());
            intent.paymentIntentOpened();

            assertThat(seats.hasSomethingToCompensate()).isTrue();
            assertThat(intent.hasSomethingToCompensate()).isTrue();
        }

        @Test
        @DisplayName("Bù trừ xong: COMPENSATED và xoá cả hai cờ — job quét không bù trừ lại")
        void bu_tru_xong_xoa_co() {
            CheckoutSaga saga = CheckoutSaga.rehydrate(
                    orderId, UUID.randomUUID(), UUID.randomUUID(), SagaStatus.COMPENSATION_PENDING, true, true, 3);

            saga.compensated();

            assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
            assertThat(saga.isSeatsReserved()).isFalse();
            assertThat(saga.isPaymentIntentOpen()).isFalse();
            assertThat(saga.hasSomethingToCompensate()).isFalse();
        }

        @Test
        @DisplayName("Bù trừ hỏng: COMPENSATION_PENDING, đếm lần thử, GIỮ nguyên cờ để thử lại")
        void bu_tru_hong_giu_co() {
            CheckoutSaga saga = CheckoutSaga.rehydrate(
                    orderId, UUID.randomUUID(), UUID.randomUUID(), SagaStatus.STARTED, true, false, 0);

            saga.compensationFailed("timeout");
            saga.compensationFailed("timeout lần hai");

            assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATION_PENDING);
            assertThat(saga.attempts()).isEqualTo(2);
            assertThat(saga.isSeatsReserved()).isTrue();
            assertThat(saga.lastError()).isEqualTo("timeout lần hai");
        }

        @Test
        @DisplayName("Thông điệp lỗi dài bị cắt ở đúng 500 ký tự; null giữ là null")
        void cat_thong_diep_loi() {
            CheckoutSaga saga = CheckoutSaga.start(orderId, UUID.randomUUID(), UUID.randomUUID());

            saga.failedBeforeAnyEffect("x".repeat(501));
            assertThat(saga.lastError()).hasSize(500);
            assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);

            saga.failedBeforeAnyEffect("y".repeat(500));
            assertThat(saga.lastError()).hasSize(500).isEqualTo("y".repeat(500));

            saga.failedBeforeAnyEffect(null);
            assertThat(saga.lastError()).isNull();
        }

        @Test
        @DisplayName("Hoàn tất: COMPLETED, cờ giữ nguyên làm nhật ký")
        void hoan_tat() {
            CheckoutSaga saga = CheckoutSaga.start(orderId, UUID.randomUUID(), UUID.randomUUID());
            saga.seatsReserved();
            saga.paymentIntentOpened();

            saga.completed();

            assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(saga.isSeatsReserved()).isTrue();
            assertThat(saga.isPaymentIntentOpen()).isTrue();
        }
    }
}
