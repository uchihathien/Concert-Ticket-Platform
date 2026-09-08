// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderItem;
import com.nexaticket.ordering.domain.model.OrderNumber;
import com.nexaticket.ordering.domain.model.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Luật tính tiền của đơn hàng — thuần domain, không cần database. */
class OrderTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    @DisplayName("Hoa hồng cộng từ TỪNG DÒNG, không tính một lần trên tổng")
    void hoa_hong_cong_tu_tung_dong() {
        // 3 vé 333.333đ, hoa hồng 5%.
        //   Cộng từ dòng: floor(333333 × 5%) = 16.666, ×3 = 49.998
        //   Tính trên tổng: floor(999.999 × 5%) = 49.999
        // Chênh 1 đồng. Vé được phát theo dòng và hoàn tiền cũng theo dòng, nên sổ cái phải
        // khớp với cách cộng theo dòng — nếu không, constraint trigger của ledger-service
        // sẽ từ chối cả bút toán.
        var order = orderOf(3, 333_333L, 500, 0L);

        assertThat(order.total().amountVnd()).isEqualTo(999_999);
        assertThat(order.commission().amountVnd()).isEqualTo(49_998);
    }

    @Test
    @DisplayName("Hoa hồng tính trên giá SAU giảm giá")
    void hoa_hong_tren_gia_sau_giam() {
        // 1 vé 1.000.000, giảm 200.000, hoa hồng 10% ⇒ 10% của 800.000 = 80.000.
        // Tính trên giá gốc sẽ là 100.000 — nền tảng ăn hoa hồng trên tiền chưa từng thu.
        var order = orderOf(1, 1_000_000L, 1_000, 200_000L);

        assertThat(order.total().amountVnd()).isEqualTo(800_000);
        assertThat(order.commission().amountVnd()).isEqualTo(80_000);
    }

    @Test
    @DisplayName("Hoa hồng 0% vẫn hợp lệ — tổ chức được miễn phí")
    void hoa_hong_khong_phan_tram() {
        assertThat(orderOf(2, 500_000L, 0, 0L).commission()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("Giảm giá vượt đơn giá bị chặn ngay ở dòng")
    void giam_gia_vuot_don_gia() {
        assertThatThrownBy(() -> new OrderItem(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "A-1",
                        "A",
                        "SEATED",
                        "1",
                        UUID.randomUUID(),
                        "Ve",
                        Money.ofVnd(100_000),
                        Money.ofVnd(200_000),
                        500))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Đơn đã PAID không bị worker hết hạn đóng")
    void don_da_thanh_toan_khong_bi_dong() {
        var order = orderOf(1, 100_000L, 500, 0L);
        order.markPaid(NOW);

        assertThat(order.close(OrderStatus.EXPIRED, NOW, "quá hạn")).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("Xác nhận thanh toán lần hai trả false và không đổi paidAt")
    void xac_nhan_lan_hai_khong_doi_gi() {
        var order = orderOf(1, 100_000L, 500, 0L);

        assertThat(order.markPaid(NOW)).isTrue();
        assertThat(order.markPaid(NOW.plusSeconds(60))).isFalse();
        assertThat(order.paidAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Mã đơn chỉ dùng bảng chữ cái đọc qua điện thoại không nhầm")
    void ma_don_khong_co_ky_tu_de_nham() {
        for (int i = 0; i < 200; i++) {
            String suffix = OrderNumber.generate(NOW).value().substring(10);
            assertThat(suffix)
                    .doesNotContain("I")
                    .doesNotContain("L")
                    .doesNotContain("O")
                    .doesNotContain("U");
        }
    }

    private static Order orderOf(int seats, long unitPriceVnd, int commissionBps, long discountPerSeatVnd) {
        List<OrderItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < seats; i++) {
            items.add(new OrderItem(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "A-" + i,
                    "A",
                    "SEATED",
                    String.valueOf(i),
                    UUID.randomUUID(),
                    "Ve ngoi",
                    Money.ofVnd(unitPriceVnd),
                    Money.ofVnd(discountPerSeatVnd),
                    commissionBps));
        }
        return Order.awaitingPayment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                items,
                null,
                NOW,
                Duration.ofMinutes(15));
    }
}
