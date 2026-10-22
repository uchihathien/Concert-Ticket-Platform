// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain;

import static com.nexaticket.ordering.support.OrderFixtures.NOW;
import static com.nexaticket.ordering.support.OrderFixtures.WINDOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.support.OrderFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Ma trận chuyển trạng thái của đơn hàng: mọi {@link OrderStatus} × mọi thao tác đổi trạng thái. */
class OrderStateTransitionTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant LATER = NOW.plusSeconds(3_600);

    @Nested
    @DisplayName("Xác nhận thanh toán (markPaid)")
    class MarkPaid {

        @Test
        @DisplayName("Đơn chờ thanh toán chuyển sang PAID và ghi đúng mốc paidAt")
        void don_cho_thanh_toan_sang_paid() {
            Order order = OrderFixtures.awaiting(USER);

            assertThat(order.markPaid(LATER)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            assertThat(order.paidAt()).isEqualTo(LATER);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"EXPIRED", "CANCELLED", "REFUNDED", "MANUAL_REVIEW"})
        @DisplayName("Đơn đã đóng hoặc đã xử lý không thể thanh toán — ném và giữ nguyên trạng thái")
        void don_da_dong_khong_the_thanh_toan(OrderStatus status) {
            Order order = OrderFixtures.inStatus(status, USER);
            Instant paidAtBefore = order.paidAt();

            assertThatThrownBy(() -> order.markPaid(LATER)).isInstanceOf(IllegalStateException.class);
            assertThat(order.status()).isEqualTo(status);
            assertThat(order.paidAt()).isEqualTo(paidAtBefore);
        }

        @Test
        @DisplayName("Đơn đã PAID đọc lại từ database: xác nhận lần nữa trả false, paidAt không đổi")
        void don_paid_doc_lai_khong_doi() {
            Order order = OrderFixtures.inStatus(OrderStatus.PAID, USER);
            Instant original = order.paidAt();

            assertThat(order.markPaid(LATER)).isFalse();
            assertThat(order.paidAt()).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("Đóng đơn (close)")
    class Close {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"EXPIRED", "CANCELLED"})
        @DisplayName("Đơn chờ thanh toán đóng được bằng EXPIRED hoặc CANCELLED, kèm mốc và lý do")
        void don_cho_thanh_toan_dong_duoc(OrderStatus reason) {
            Order order = OrderFixtures.awaiting(USER);

            assertThat(order.close(reason, LATER, "ly do")).isTrue();
            assertThat(order.status()).isEqualTo(reason);
            assertThat(order.closedAt()).isEqualTo(LATER);
            assertThat(order.closeReason()).isEqualTo("ly do");
            assertThat(order.paidAt()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"AWAITING_PAYMENT"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Đơn không còn chờ thanh toán: close trả false và không chạm vào gì")
        void don_khong_con_mo_khong_dong_lai(OrderStatus status) {
            Order order = OrderFixtures.inStatus(status, USER);

            assertThat(order.close(OrderStatus.EXPIRED, LATER, "qua han")).isFalse();
            assertThat(order.status()).isEqualTo(status);
            assertThat(order.closedAt()).isNull();
            assertThat(order.closeReason()).isNull();
        }

        @Test
        @DisplayName("Lý do đóng là AWAITING_PAYMENT bị từ chối")
        void ly_do_dong_dang_mo_bi_tu_choi() {
            Order order = OrderFixtures.awaiting(USER);

            assertThatThrownBy(() -> order.close(OrderStatus.AWAITING_PAYMENT, LATER, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }

        /**
         * Đóng đơn chỉ có hai kết cục hợp lệ (EXPIRED, CANCELLED). Nhận PAID làm "lý do đóng" là một
         * đường vào PAID không có paidAt và không qua markPaid — chính thứ ràng buộc ck_paid_has_timestamp
         * và luồng idempotent của webhook dựa vào.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"PAID", "REFUNDED", "MANUAL_REVIEW"})
        @DisplayName("Lý do đóng không phải EXPIRED/CANCELLED bị từ chối — không có đường tắt vào PAID")
        void ly_do_dong_khong_phai_ket_cuc_dong(OrderStatus reason) {
            Order order = OrderFixtures.awaiting(USER);

            assertThatThrownBy(() -> order.close(reason, LATER, "x")).isInstanceOf(IllegalArgumentException.class);
            assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }
    }

    @Nested
    @DisplayName("Tiền vào đơn đã đóng (flagManualReview)")
    class ManualReview {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"EXPIRED", "CANCELLED"})
        @DisplayName("Đơn đã đóng sang MANUAL_REVIEW, KHÔNG có paidAt")
        void don_da_dong_sang_doi_soat(OrderStatus status) {
            Order order = OrderFixtures.inStatus(status, USER);

            assertThat(order.flagManualReview(LATER, "Tiền vào sau khi đơn đã " + status))
                    .isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.MANUAL_REVIEW);
            assertThat(order.paidAt()).isNull();
            assertThat(order.closedAt()).isEqualTo(LATER);
            assertThat(order.closeReason()).contains(status.name());
        }

        @Test
        @DisplayName("Đơn đã MANUAL_REVIEW: lần hai trả false, giữ nguyên mốc lần đầu")
        void doi_soat_lan_hai_khong_doi() {
            Order order = OrderFixtures.inStatus(OrderStatus.EXPIRED, USER);
            order.flagManualReview(LATER, "lan dau");

            assertThat(order.flagManualReview(LATER.plusSeconds(10), "lan hai")).isFalse();
            assertThat(order.closedAt()).isEqualTo(LATER);
            assertThat(order.closeReason()).isEqualTo("lan dau");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"AWAITING_PAYMENT", "PAID"})
        @DisplayName("Đơn đang mở hoặc đã PAID không cần đối soát tay — ném")
        void don_mo_hoac_da_tra_khong_doi_soat(OrderStatus status) {
            Order order = OrderFixtures.inStatus(status, USER);

            assertThatThrownBy(() -> order.flagManualReview(LATER, "x")).isInstanceOf(IllegalStateException.class);
            assertThat(order.status()).isEqualTo(status);
        }
    }

    @Nested
    @DisplayName("Hạn thanh toán (isExpiredAt) — biên")
    class Expiry {

        @Test
        @DisplayName("Một nano giây trước hạn: chưa hết hạn")
        void truoc_han_chua_het() {
            Order order = OrderFixtures.awaiting(USER);

            assertThat(order.isExpiredAt(NOW.plus(WINDOW).minusNanos(1))).isFalse();
        }

        @Test
        @DisplayName("Đúng mốc hạn: đã hết hạn (nửa mở)")
        void dung_moc_la_het_han() {
            Order order = OrderFixtures.awaiting(USER);

            assertThat(order.paymentExpiresAt()).isEqualTo(NOW.plus(WINDOW));
            assertThat(order.isExpiredAt(NOW.plus(WINDOW))).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"AWAITING_PAYMENT"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Đơn không còn chờ tiền thì không bao giờ 'hết hạn', dù đã quá mốc")
        void don_khong_mo_khong_het_han(OrderStatus status) {
            Order order = OrderFixtures.inStatus(status, USER);

            assertThat(order.isExpiredAt(LATER.plusSeconds(86_400))).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OrderStatus.class)
    @DisplayName("Chỉ AWAITING_PAYMENT là trạng thái mở")
    void chi_cho_thanh_toan_la_mo(OrderStatus status) {
        assertThat(status.isOpen()).isEqualTo(status == OrderStatus.AWAITING_PAYMENT);
    }
}
