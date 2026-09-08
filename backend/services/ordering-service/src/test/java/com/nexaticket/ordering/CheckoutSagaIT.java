// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ordering.application.command.CompensationSweeper;
import com.nexaticket.ordering.application.command.PlaceOrderHandler;
import com.nexaticket.ordering.domain.model.SagaStatus;
import com.nexaticket.ordering.domain.port.CheckoutSagaRepository;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.support.FakeRemoteServices;
import com.nexaticket.ordering.support.OrderingTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Saga checkout và ba lớp bù trừ (sagas.md §2).
 *
 * <p>Phần đáng test nhất của service này không phải đường thành công — nó chỉ là bốn lời gọi nối
 * nhau. Đáng test là <b>các đường hỏng</b>: mỗi lần một service ngoài không trả lời, ghế phải quay
 * về trạng thái bán được, nếu không mỗi sự cố mạng sẽ ăn mất một ít tồn kho vĩnh viễn.
 */
class CheckoutSagaIT extends OrderingTestBase {

    @Autowired
    PlaceOrderHandler placeOrder;

    @Autowired
    CompensationSweeper sweeper;

    @Autowired
    OrderRepository orders;

    @Autowired
    CheckoutSagaRepository sagas;

    @Autowired
    FakeRemoteServices.FakeInventory inventory;

    @Autowired
    FakeRemoteServices.FakePayment payment;

    @Autowired
    FakeRemoteServices.FakePricing pricing;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetFakes() {
        inventory.reserveTimesOut = false;
        inventory.refuseWithCode = null;
        inventory.cancelFails = false;
        inventory.cancelledOrders.clear();
        inventory.reserveCalls.set(0);
        inventory.seatCount = 2;
        inventory.seatPriceVnd = 1_500_000L;
        payment.openFails = false;
        payment.cancelledIntents.clear();
        pricing.commissionBps = 500;
        pricing.discountVnd = 0;
        pricing.promotionInvalid = false;
        pricing.timesOut = false;
    }

    @Test
    @DisplayName("Đường thành công: đơn 2 vé × 1.500.000, hoa hồng 5% = 150.000")
    void duong_thanh_cong() {
        var result = placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), null));

        assertThat(result.totalVnd()).isEqualTo(3_000_000);
        assertThat(result.paymentReference()).startsWith("NT");
        assertThat(result.vietQrPayload()).isNotBlank();

        var order = orders.findById(result.orderId()).orElseThrow();
        assertThat(order.items()).hasSize(2);
        assertThat(order.commission().amountVnd()).isEqualTo(150_000);
        assertThat(sagas.findByOrderId(result.orderId()).orElseThrow().status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("Payment không phản hồi: chỗ đã đặt phải được nhả, saga COMPENSATED")
    void payment_hong_thi_nha_cho() {
        payment.openFails = true;

        assertThat(codeOf(() ->
                        placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), null))))
                .isEqualTo("CHECKOUT_UNAVAILABLE");

        assertThat(inventory.cancelledOrders).hasSize(1);
        var saga = sagas.findByOrderId(inventory.cancelledOrders.get(0)).orElseThrow();
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("Inventory từ chối vì nghiệp vụ: KHÔNG bù trừ, vì nó chưa đổi gì")
    void inventory_tu_choi_thi_khong_bu_tru() {
        inventory.refuseWithCode = "HOLD_EXPIRED";

        assertThat(codeOf(() ->
                        placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), null))))
                .isEqualTo("HOLD_EXPIRED");

        // Gọi cancelReservation ở đây là thừa và có thể nhả nhầm chỗ của đơn khác.
        assertThat(inventory.cancelledOrders).isEmpty();
    }

    @Test
    @DisplayName("Mã khuyến mãi sai: nhả chỗ đã đặt rồi mới trả lỗi")
    void khuyen_mai_sai_thi_van_nha_cho() {
        pricing.promotionInvalid = true;

        assertThat(codeOf(() ->
                        placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), "SAI"))))
                .isEqualTo("PROMOTION_INVALID");

        // Chỗ đã RESERVED ở bước trước, nên lần này BẮT BUỘC phải bù trừ.
        assertThat(inventory.cancelledOrders).hasSize(1);
    }

    @Test
    @DisplayName("Bù trừ cũng hỏng: saga vào COMPENSATION_PENDING, job quét dọn nốt")
    void bu_tru_hong_thi_job_quet_don_not() {
        payment.openFails = true;
        inventory.cancelFails = true;
        UUID holdId = UUID.randomUUID();

        assertThatThrownBy(() -> placeOrder.handle(new PlaceOrderHandler.Command(holdId, UUID.randomUUID(), null)))
                .isInstanceOf(ApiException.class);

        // Lưới thứ nhất hỏng — saga chờ lưới thứ hai.
        // Lọc theo holdId vì các lớp test dùng chung một database: đếm toàn cục sẽ vô tình
        // đụng saga do test khác để lại và biến test này thành phụ thuộc thứ tự chạy.
        var mine = sagas.claimCompensationPending(java.time.Instant.now(), 50).stream()
                .filter(saga -> saga.holdId().equals(holdId))
                .toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).status()).isEqualTo(SagaStatus.COMPENSATION_PENDING);

        // Đẩy lùi updated_at để vượt backoff 30 giây, thay vì thật sự chờ 30 giây trong test.
        // Chính backoff đó là thứ test kế bên khẳng định, nên không thể bỏ nó đi cho tiện.
        jdbc.update(
                "UPDATE checkout_sagas SET updated_at = now() - interval '5 minutes' WHERE order_id = ?",
                mine.get(0).orderId());

        // Inventory sống lại, job quét bù trừ thành công.
        inventory.cancelFails = false;
        sweeper.runOnce();

        assertThat(inventory.cancelledOrders).contains(mine.get(0).orderId());
        assertThat(sagas.findByOrderId(mine.get(0).orderId()).orElseThrow().status())
                .isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("Job quét chờ một nhịp trước khi thử lại, không đập liên tục vào service đang hỏng")
    void job_quet_cho_mot_nhip() {
        payment.openFails = true;
        inventory.cancelFails = true;
        UUID holdId = UUID.randomUUID();
        assertThatThrownBy(() -> placeOrder.handle(new PlaceOrderHandler.Command(holdId, UUID.randomUUID(), null)))
                .isInstanceOf(ApiException.class);

        // Mọi saga đang chờ đều vừa hỏng trong vài giây qua, chưa cái nào đủ backoff 30 giây.
        assertThat(sweeper.runOnce()).isZero();
        assertThat(sagas.claimCompensationPending(java.time.Instant.now(), 50))
                .anyMatch(saga -> saga.holdId().equals(holdId));
    }

    @Test
    @DisplayName("Bấm thanh toán hai lần cùng một lần giữ chỗ: chỉ một đơn, trả lại đúng mã QR cũ")
    void bam_hai_lan_chi_mot_don() {
        UUID holdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        var first = placeOrder.handle(new PlaceOrderHandler.Command(holdId, userId, null));
        var second = placeOrder.handle(new PlaceOrderHandler.Command(holdId, userId, null));

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.vietQrPayload()).isEqualTo(first.vietQrPayload());
        // Lần thứ hai không được gọi lại Inventory: nó sẽ đặt chỗ thêm một lần nữa.
        assertThat(inventory.reserveCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Giảm giá chia xuống dòng, phần dư dồn vào dòng cuối để tổng không lệch")
    void giam_gia_chia_xuong_dong_khong_lech_dong_nao() {
        inventory.seatCount = 3;
        inventory.seatPriceVnd = 1_000_000L;
        pricing.discountVnd = 10_000L; // 10.000 chia 3 không hết

        var result = placeOrder.handle(new PlaceOrderHandler.Command(UUID.randomUUID(), UUID.randomUUID(), "GIAM"));
        var order = orders.findById(result.orderId()).orElseThrow();

        assertThat(order.discount().amountVnd()).isEqualTo(10_000);
        assertThat(result.totalVnd()).isEqualTo(3_000_000 - 10_000);
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException e) {
            return e.errorCode().code();
        }
    }
}
