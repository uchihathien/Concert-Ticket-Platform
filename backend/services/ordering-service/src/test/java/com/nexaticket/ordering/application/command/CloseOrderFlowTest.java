// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import com.nexaticket.ordering.support.OrderFixtures;
import com.nexaticket.ordering.support.RecordingSagaRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/**
 * Đóng đơn: khách tự huỷ ({@link CloseOrderHandler}) và worker hết hạn ({@link ExpireOrdersJob}),
 * cùng các transaction ngắn của {@link OrderTransactions} mà hai đường này dựa vào.
 */
class CloseOrderFlowTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:30:00Z");

    private OrderRepository orders;
    private OutboxPort outbox;
    private PaymentPort payments;
    private InventoryPort inventory;
    private OrderTransactions tx;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        outbox = mock(OutboxPort.class);
        payments = mock(PaymentPort.class);
        inventory = mock(InventoryPort.class);
        tx = new OrderTransactions(orders, new RecordingSagaRepository(), outbox);
    }

    private static OrderingErrorCode codeOf(Throwable e) {
        return (OrderingErrorCode) ((ApiException) e).errorCode();
    }

    @Nested
    @DisplayName("Khách tự huỷ đơn")
    class CancelByUser {

        private final UUID owner = UUID.randomUUID();
        private CloseOrderHandler handler;

        @BeforeEach
        void setUp() {
            handler = new CloseOrderHandler(tx, payments, inventory, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private Order stored(Order order) {
            when(orders.findById(order.id())).thenReturn(Optional.of(order));
            return order;
        }

        @Test
        @DisplayName("Chủ đơn huỷ đơn chờ tiền: CANCELLED, ghi, bắn sự kiện, đóng link TRƯỚC rồi nhả chỗ")
        void chu_don_huy_duoc() {
            Order order = stored(OrderFixtures.awaiting(owner));

            handler.cancel(order.id(), owner);

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.closedAt()).isEqualTo(NOW);
            verify(orders).updateStatus(order);
            verify(outbox).orderClosed(order);
            InOrder sequence = inOrder(payments, inventory);
            sequence.verify(payments).cancelIntent(order.id());
            sequence.verify(inventory).cancelReservation(order.id());
        }

        @Test
        @DisplayName("Huỷ đơn của người khác: 404 (không phải 403), đơn không đổi, không gọi service nào")
        void huy_don_nguoi_khac_404() {
            Order order = stored(OrderFixtures.awaiting(owner));

            assertThatThrownBy(() -> handler.cancel(order.id(), UUID.randomUUID()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND));

            assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            verify(orders, never()).updateStatus(any());
            verifyNoInteractions(outbox, payments, inventory);
        }

        @Test
        @DisplayName("Đơn không tồn tại: 404")
        void don_khong_ton_tai() {
            UUID missing = UUID.randomUUID();
            when(orders.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.cancel(missing, owner))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND));
            verifyNoInteractions(payments, inventory);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = OrderStatus.class,
                names = {"AWAITING_PAYMENT"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Đơn không còn chờ tiền: 409 ORDER_NOT_OPEN, không ghi, không nhả chỗ của đơn đã trả")
        void don_khong_mo_409(OrderStatus status) {
            Order order = stored(OrderFixtures.inStatus(status, owner));

            assertThatThrownBy(() -> handler.cancel(order.id(), owner))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.ORDER_NOT_OPEN));

            assertThat(order.status()).isEqualTo(status);
            verify(orders, never()).updateStatus(any());
            verifyNoInteractions(outbox, payments, inventory);
        }

        @Test
        @DisplayName("Payment hỏng khi đóng link: đơn vẫn huỷ, ghế vẫn được nhả, không ném ra khách")
        void payment_hong_van_nha_cho() {
            Order order = stored(OrderFixtures.awaiting(owner));
            doThrow(new RemoteCallException("payment", "timeout", null))
                    .when(payments)
                    .cancelIntent(order.id());

            handler.cancel(order.id(), owner);

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(inventory).cancelReservation(order.id());
        }

        @Test
        @DisplayName("Inventory hỏng khi nhả chỗ: đơn vẫn huỷ, không ném — consumer order.cancelled dọn nốt")
        void inventory_hong_van_huy() {
            Order order = stored(OrderFixtures.awaiting(owner));
            doThrow(new RemoteCallException("inventory", "timeout", null))
                    .when(inventory)
                    .cancelReservation(order.id());

            handler.cancel(order.id(), owner);

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(outbox).orderClosed(order);
        }
    }

    @Nested
    @DisplayName("Worker hết hạn")
    class Expiry {

        private ExpireOrdersJob job;

        @BeforeEach
        void setUp() {
            job = new ExpireOrdersJob(tx, payments, inventory, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("Chỉ nhả chỗ cho đơn THỰC SỰ vừa đóng; đơn vừa PAID giữa chừng thì bỏ qua")
        void chi_nha_cho_don_vua_dong() {
            Order expired = OrderFixtures.awaiting(UUID.randomUUID());
            Order raced = OrderFixtures.inStatus(OrderStatus.PAID, UUID.randomUUID());
            when(orders.claimExpired(NOW, 200)).thenReturn(List.of(expired, raced));

            int closed = job.runOnce();

            assertThat(closed).isEqualTo(1);
            assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(raced.status()).isEqualTo(OrderStatus.PAID);
            verify(outbox).orderClosed(expired);
            verify(outbox, never()).orderClosed(raced);
            verify(inventory).cancelReservation(expired.id());
            verify(inventory, never()).cancelReservation(raced.id());
            verify(payments, never()).cancelIntent(raced.id());
        }

        @Test
        @DisplayName("Mỗi đơn: đóng link thanh toán TRƯỚC khi nhả ghế")
        void dong_link_truoc_nha_ghe() {
            Order expired = OrderFixtures.awaiting(UUID.randomUUID());
            when(orders.claimExpired(NOW, 200)).thenReturn(List.of(expired));

            job.runOnce();

            InOrder sequence = inOrder(payments, inventory);
            sequence.verify(payments).cancelIntent(expired.id());
            sequence.verify(inventory).cancelReservation(expired.id());
        }

        @Test
        @DisplayName("Một đơn hỏng khi dọn không chặn các đơn sau; mọi đơn vẫn đã đóng")
        void mot_don_hong_khong_chan_don_sau() {
            Order first = OrderFixtures.awaiting(UUID.randomUUID());
            Order second = OrderFixtures.awaiting(UUID.randomUUID());
            when(orders.claimExpired(NOW, 200)).thenReturn(List.of(first, second));
            doThrow(new RemoteCallException("payment", "down", null))
                    .when(payments)
                    .cancelIntent(first.id());
            doThrow(new RemoteCallException("inventory", "down", null))
                    .when(inventory)
                    .cancelReservation(first.id());

            assertThat(job.runOnce()).isEqualTo(2);

            verify(payments).cancelIntent(second.id());
            verify(inventory).cancelReservation(second.id());
            assertThat(first.status()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(second.status()).isEqualTo(OrderStatus.EXPIRED);
        }

        @Test
        @DisplayName("Không có đơn quá hạn: trả 0, không gọi service nào")
        void khong_co_don() {
            when(orders.claimExpired(NOW, 200)).thenReturn(List.of());

            assertThat(job.runOnce()).isZero();
            verifyNoInteractions(payments, inventory, outbox);
        }
    }
}
