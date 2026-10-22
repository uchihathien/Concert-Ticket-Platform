// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderItem;
import com.nexaticket.ordering.domain.model.SagaStatus;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.PricingPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import com.nexaticket.ordering.support.OrderFixtures;
import com.nexaticket.ordering.support.RecordingSagaRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Saga checkout ở mức đơn vị: giá tính ở server, idempotent theo holdId, và bù trừ khi từng bước
 * hỏng. Database và ba service ngoài đều là hàng giả; {@link OrderTransactions} là bản thật.
 */
class PlaceOrderHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UUID userId = UUID.randomUUID();
    private final UUID holdId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    private OrderRepository orders;
    private PricingPort pricing;
    private InventoryPort inventory;
    private PaymentPort payments;
    private OutboxPort outbox;
    private RecordingSagaRepository sagas;
    private PlaceOrderHandler handler;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        pricing = mock(PricingPort.class);
        inventory = mock(InventoryPort.class);
        payments = mock(PaymentPort.class);
        outbox = mock(OutboxPort.class);
        sagas = new RecordingSagaRepository();
        handler = new PlaceOrderHandler(
                orders,
                pricing,
                inventory,
                payments,
                new OrderTransactions(orders, sagas, outbox),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new OrderingProperties(WINDOW, Duration.ofSeconds(2)));

        when(orders.findByHoldId(holdId)).thenReturn(Optional.empty());
        when(inventory.reserve(any(), eq(holdId), eq(userId))).thenReturn(reservation(1_500_000L, 1_500_000L));
        when(pricing.resolve(eq(sessionId), any(), anyLong())).thenReturn(new PricingPort.Pricing(500, 0));
        when(payments.openIntent(any(), any(), anyLong(), any())).thenReturn(intent("NT1000001"));
    }

    private PlaceOrderHandler.Command command(String promotionCode) {
        return new PlaceOrderHandler.Command(holdId, userId, promotionCode);
    }

    private InventoryPort.Reservation reservation(long... prices) {
        List<InventoryPort.Seat> seats = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            seats.add(new InventoryPort.Seat(
                    UUID.randomUUID(), "A-" + i, "A", "SEATED", String.valueOf(i), UUID.randomUUID(), "Ve", prices[i]));
        }
        return new InventoryPort.Reservation(sessionId, eventId, organizationId, seats);
    }

    private static PaymentPort.Intent intent(String reference) {
        return new PaymentPort.Intent(
                reference, "000201...6304ABCD", "https://pay.payos.vn/web/" + reference, "970422", "V3", "NEXA");
    }

    private static OrderingErrorCode codeOf(Throwable e) {
        return (OrderingErrorCode) ((ApiException) e).errorCode();
    }

    private Order savedOrder() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Đường thành công")
    class HappyPath {

        @Test
        @DisplayName("Giá lấy từ Inventory, giảm giá từ Catalog; số tiền mở link = tổng server tự tính")
        void gia_tinh_o_server() {
            when(pricing.resolve(sessionId, "SALE", 3_000_000L)).thenReturn(new PricingPort.Pricing(500, 300_000));

            PlaceOrderHandler.Result result = handler.handle(command("SALE"));

            verify(pricing).resolve(sessionId, "SALE", 3_000_000L);
            verify(payments).openIntent(result.orderId(), organizationId, 2_700_000L, NOW.plus(WINDOW));
            assertThat(result.totalVnd()).isEqualTo(2_700_000L);
            assertThat(result.paymentExpiresAt()).isEqualTo(NOW.plus(WINDOW));
            assertThat(result.paymentReference()).isEqualTo("NT1000001");
            assertThat(result.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/NT1000001");
            assertThat(result.vietQrPayload()).isEqualTo("000201...6304ABCD");

            Order saved = savedOrder();
            assertThat(saved.id()).isEqualTo(result.orderId());
            assertThat(saved.userId()).isEqualTo(userId);
            assertThat(saved.holdId()).isEqualTo(holdId);
            assertThat(saved.eventId()).isEqualTo(eventId);
            assertThat(saved.promotionCode()).isEqualTo("SALE");
            assertThat(saved.commission().amountVnd()).isEqualTo(135_000L); // 5% của 2.700.000
            verify(outbox).orderCreated(saved);
            assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPLETED);
        }

        @Test
        @DisplayName("Cờ saga được ghi xuống TRƯỚC mỗi lời gọi có tác dụng phụ")
        void ghi_co_truoc_khi_goi() {
            List<RecordingSagaRepository.Snapshot> atReserve = new ArrayList<>();
            List<RecordingSagaRepository.Snapshot> atOpenIntent = new ArrayList<>();
            when(inventory.reserve(any(), eq(holdId), eq(userId))).thenAnswer(inv -> {
                atReserve.add(sagas.last());
                return reservation(1_500_000L);
            });
            when(payments.openIntent(any(), any(), anyLong(), any())).thenAnswer(inv -> {
                atOpenIntent.add(sagas.last());
                return intent("NT1000002");
            });

            handler.handle(command(null));

            assertThat(sagas.writes.get(0).op()).isEqualTo("save");
            assertThat(sagas.writes.get(0).status()).isEqualTo(SagaStatus.STARTED);
            assertThat(atReserve).singleElement().satisfies(s -> {
                assertThat(s.seatsReserved()).isTrue();
                assertThat(s.intentOpen()).isFalse();
            });
            assertThat(atOpenIntent).singleElement().satisfies(s -> assertThat(s.intentOpen())
                    .isTrue());
        }

        @Test
        @DisplayName("Giảm giá chia theo tỷ lệ giá vé, phần dư dồn dòng cuối, tổng dòng = tổng giảm")
        void giam_gia_chia_theo_ty_le() {
            when(inventory.reserve(any(), eq(holdId), eq(userId))).thenReturn(reservation(1_000_000L, 2_000_000L));
            when(pricing.resolve(sessionId, null, 3_000_000L)).thenReturn(new PricingPort.Pricing(500, 100_000));

            handler.handle(command(null));

            List<OrderItem> items = savedOrder().items();
            assertThat(items).extracting(i -> i.discount().amountVnd()).containsExactly(33_333L, 66_667L);
            assertThat(savedOrder().total().amountVnd()).isEqualTo(2_900_000L);
        }

        /**
         * Vé mời 0đ nằm cuối đơn cùng vé có giá, kèm một mã giảm giá. Phần dư làm tròn dồn vào dòng
         * cuối thì dòng 0đ nhận 1đ giảm giá — vượt đơn giá, OrderItem từ chối, và khách nhận "thử lại"
         * mãi cho một đơn hoàn toàn hợp lệ.
         */
        @Test
        @DisplayName("Vé 0đ ở cuối đơn + mã giảm giá: đơn vẫn đặt được, không dòng nào giảm quá giá")
        void ve_0d_cuoi_don_kem_giam_gia() {
            when(inventory.reserve(any(), eq(holdId), eq(userId))).thenReturn(reservation(1_000L, 2_000L, 0L));
            when(pricing.resolve(sessionId, "SALE", 3_000L)).thenReturn(new PricingPort.Pricing(500, 100));

            PlaceOrderHandler.Result result = handler.handle(command("SALE"));

            assertThat(result.totalVnd()).isEqualTo(2_900L);
            assertThat(savedOrder().items())
                    .allSatisfy(i -> assertThat(i.discount().amountVnd())
                            .isLessThanOrEqualTo(i.unitPrice().amountVnd()));
        }
    }

    @Nested
    @DisplayName("Idempotent theo holdId")
    class Idempotency {

        @Test
        @DisplayName("Đơn đã có cho lần giữ chỗ này: trả lại đúng đơn cũ, không gọi service nào")
        void tra_lai_don_cu() {
            Order existing = OrderFixtures.awaiting(userId);
            existing.attachPayment("NT7777777", "qr-cu", "https://pay.payos.vn/web/7777777");
            when(orders.findByHoldId(holdId)).thenReturn(Optional.of(existing));

            PlaceOrderHandler.Result result = handler.handle(command(null));

            assertThat(result.orderId()).isEqualTo(existing.id());
            assertThat(result.paymentReference()).isEqualTo("NT7777777");
            assertThat(result.vietQrPayload()).isEqualTo("qr-cu");
            verifyNoInteractions(inventory, pricing, payments, outbox);
            assertThat(sagas.writes).isEmpty();
        }

        /**
         * Đường idempotent bỏ qua Inventory — nơi duy nhất kiểm "lần giữ chỗ này có phải của bạn".
         * Người khác gửi đúng holdId sẽ nhận về đơn của chủ hold, kèm link thanh toán và mã QR.
         */
        @Test
        @DisplayName("holdId đã thành đơn của NGƯỜI KHÁC: không được trả đơn đó ra")
        void hold_cua_nguoi_khac_khong_tra_don() {
            Order foreign = OrderFixtures.awaiting(UUID.randomUUID());
            when(orders.findByHoldId(holdId)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> handler.handle(command(null))).isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("Đụng unique hold_id lúc ghi: bù trừ phần của mình rồi trả đơn đang có")
        void dung_unique_hold_thi_bu_tru_va_tra_don_co() {
            Order winner = OrderFixtures.awaiting(userId);
            when(orders.findByHoldId(holdId)).thenReturn(Optional.empty(), Optional.of(winner));
            doThrow(new OrderRepository.DuplicateHoldException(null))
                    .when(orders)
                    .save(any());

            PlaceOrderHandler.Result result = handler.handle(command(null));

            assertThat(result.orderId()).isEqualTo(winner.id());
            verify(payments).cancelIntent(any());
            verify(inventory).cancelReservation(any());
            verify(outbox, never()).orderCreated(any());
            assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPENSATED);
        }

        @Test
        @DisplayName("Đụng unique hold_id mà không đọc lại được đơn: 503 thử lại, không 500")
        void dung_unique_ma_khong_thay_don() {
            doThrow(new OrderRepository.DuplicateHoldException(null))
                    .when(orders)
                    .save(any());

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.CHECKOUT_UNAVAILABLE));
        }
    }

    @Nested
    @DisplayName("Hỏng và bù trừ")
    class FailureRecovery {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "HOLD_EXPIRED, HOLD_EXPIRED",
            "HOLD_NOT_FOUND, HOLD_EXPIRED",
            "HOLD_NOT_OWNED, HOLD_EXPIRED",
            "SEAT_UNAVAILABLE, SEAT_UNAVAILABLE",
            "MA_LA_KHONG_BIET, SEAT_UNAVAILABLE"
        })
        @DisplayName("Inventory từ chối dứt khoát: dịch mã lỗi, saga FAILED, không bù trừ gì")
        void inventory_tu_choi(String inventoryCode, OrderingErrorCode expected) {
            when(inventory.reserve(any(), any(), any()))
                    .thenThrow(new InventoryPort.SeatsUnavailableException(inventoryCode, "tu choi"));

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(expected));

            assertThat(sagas.last().status()).isEqualTo(SagaStatus.FAILED);
            verify(inventory, never()).cancelReservation(any());
            verifyNoInteractions(pricing, payments, outbox);
            verify(orders, never()).save(any());
        }

        @Test
        @DisplayName("Inventory timeout: giả định đã đặt chỗ ⇒ nhả chỗ, không đụng payment, 503")
        void inventory_timeout() {
            when(inventory.reserve(any(), any(), any()))
                    .thenThrow(new RemoteCallException("inventory", "timeout", null));

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.CHECKOUT_UNAVAILABLE));

            verify(inventory).cancelReservation(any());
            verifyNoInteractions(pricing, payments);
            assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPENSATED);
        }

        @Test
        @DisplayName("Mã khuyến mãi sai: 400 PROMOTION_INVALID, nhả chỗ, không mở link thanh toán")
        void khuyen_mai_sai() {
            when(pricing.resolve(any(), any(), anyLong()))
                    .thenThrow(new PricingPort.PromotionInvalidException("het han"));

            assertThatThrownBy(() -> handler.handle(command("HETHAN")))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.PROMOTION_INVALID));

            verify(inventory).cancelReservation(any());
            verifyNoInteractions(payments);
            assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPENSATED);
        }

        @Test
        @DisplayName("Catalog timeout: 503, nhả chỗ")
        void catalog_timeout() {
            when(pricing.resolve(any(), any(), anyLong()))
                    .thenThrow(new RemoteCallException("catalog", "timeout", null));

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.CHECKOUT_UNAVAILABLE));

            verify(inventory).cancelReservation(any());
            verifyNoInteractions(payments);
        }

        @Test
        @DisplayName("Payment timeout: đóng intent TRƯỚC rồi mới nhả chỗ (ngược thứ tự làm), không ghi đơn")
        void payment_timeout_bu_tru_nguoc_thu_tu() {
            when(payments.openIntent(any(), any(), anyLong(), any()))
                    .thenThrow(new RemoteCallException("payment", "timeout", null));

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.CHECKOUT_UNAVAILABLE));

            InOrder order = inOrder(payments, inventory);
            order.verify(payments).cancelIntent(any());
            order.verify(inventory).cancelReservation(any());
            verify(orders, never()).save(any());
            verifyNoInteractions(outbox);
            assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPENSATED);
            assertThat(sagas.last().seatsReserved()).isFalse();
        }

        @Test
        @DisplayName("Bù trừ cũng hỏng: COMPENSATION_PENDING, cờ còn nguyên cho job quét, khách vẫn nhận 503")
        void bu_tru_hong() {
            when(payments.openIntent(any(), any(), anyLong(), any()))
                    .thenThrow(new RemoteCallException("payment", "timeout", null));
            doThrow(new RemoteCallException("payment", "van hong", null))
                    .when(payments)
                    .cancelIntent(any());

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.CHECKOUT_UNAVAILABLE));

            RecordingSagaRepository.Snapshot last = sagas.last();
            assertThat(last.status()).isEqualTo(SagaStatus.COMPENSATION_PENDING);
            assertThat(last.attempts()).isEqualTo(1);
            assertThat(last.seatsReserved()).isTrue();
            assertThat(last.intentOpen()).isTrue();
        }

        @Test
        @DisplayName("Lỗi ngoài dự kiến khi ghi đơn: bù trừ cả hai bước, trả 503 chứ không 500")
        void loi_ngoai_du_kien_khi_ghi_don() {
            doThrow(new IllegalStateException("db chet")).when(orders).save(any());

            assertThatThrownBy(() -> handler.handle(command(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.CHECKOUT_UNAVAILABLE));

            verify(payments).cancelIntent(any());
            verify(inventory).cancelReservation(any());
            verifyNoInteractions(outbox);
            assertThat(sagas.last().status()).isEqualTo(SagaStatus.COMPENSATED);
        }
    }
}
