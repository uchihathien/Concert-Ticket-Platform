// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.application.command.ConfirmPaymentHandler.Outcome;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.domain.port.OutboxPort;
import com.nexaticket.ordering.support.OrderFixtures;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Xác nhận thanh toán từ payment-service: một câu trả lời dứt khoát cho mọi trạng thái đơn. */
class ConfirmPaymentHandlerTest {

    private static final Instant WEBHOOK_AT = Instant.parse("2026-09-08T10:20:00Z");

    private OrderRepository orders;
    private OutboxPort outbox;
    private ConfirmPaymentHandler handler;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        outbox = mock(OutboxPort.class);
        handler = new ConfirmPaymentHandler(orders, outbox, Clock.fixed(WEBHOOK_AT, ZoneOffset.UTC));
    }

    private Order stored(Order order) {
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("Đơn chờ tiền: sang PAID với paidAt = giờ đồng hồ, ghi và bắn order.paid đúng một lần")
    void don_cho_tien_sang_paid() {
        Order order = stored(OrderFixtures.awaiting(UUID.randomUUID()));

        assertThat(handler.handle(order.id())).isEqualTo(Outcome.PAID);

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.paidAt()).isEqualTo(WEBHOOK_AT);
        verify(orders).updateStatus(order);
        verify(outbox).orderPaid(order);
    }

    @Test
    @DisplayName("Đơn đã PAID: ALREADY_PAID, không ghi, không bắn sự kiện — ticketing không phát vé đôi")
    void da_paid_khong_lam_gi() {
        Order order = stored(OrderFixtures.inStatus(OrderStatus.PAID, UUID.randomUUID()));
        Instant paidAt = order.paidAt();

        assertThat(handler.handle(order.id())).isEqualTo(Outcome.ALREADY_PAID);

        assertThat(order.paidAt()).isEqualTo(paidAt);
        verify(orders, never()).updateStatus(any());
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = OrderStatus.class,
            names = {"EXPIRED", "CANCELLED"})
    @DisplayName("Tiền vào đơn đã đóng: MANUAL_REVIEW, ghi lại kèm trạng thái cũ, KHÔNG bắn order.paid")
    void tien_vao_don_da_dong(OrderStatus closed) {
        Order order = stored(OrderFixtures.inStatus(closed, UUID.randomUUID()));

        assertThat(handler.handle(order.id())).isEqualTo(Outcome.MANUAL_REVIEW);

        assertThat(order.status()).isEqualTo(OrderStatus.MANUAL_REVIEW);
        assertThat(order.closeReason()).contains(closed.name());
        assertThat(order.closedAt()).isEqualTo(WEBHOOK_AT);
        assertThat(order.paidAt()).isNull();
        verify(orders).updateStatus(order);
        verify(outbox, never()).orderPaid(any());
    }

    @Test
    @DisplayName("Đơn đã MANUAL_REVIEW nhận xác nhận trùng: vẫn MANUAL_REVIEW, không ghi lại")
    void doi_soat_trung() {
        Order order = stored(OrderFixtures.inStatus(OrderStatus.MANUAL_REVIEW, UUID.randomUUID()));

        assertThat(handler.handle(order.id())).isEqualTo(Outcome.MANUAL_REVIEW);

        verify(orders, never()).updateStatus(any());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Đơn không tồn tại: 404 ORDER_NOT_FOUND, không ghi gì")
    void don_khong_ton_tai() {
        UUID missing = UUID.randomUUID();
        when(orders.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(missing))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e -> assertThat(((ApiException) e).errorCode()).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND));
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Hai webhook liên tiếp trên cùng đơn: lần một PAID, lần hai ALREADY_PAID, một sự kiện duy nhất")
    void hai_webhook_lien_tiep() {
        Order order = stored(OrderFixtures.awaiting(UUID.randomUUID()));

        Outcome first = handler.handle(order.id());
        Outcome second = handler.handle(order.id());

        assertThat(first).isEqualTo(Outcome.PAID);
        assertThat(second).isEqualTo(Outcome.ALREADY_PAID);
        verify(outbox).orderPaid(order);
        assertThat(order.paidAt()).isEqualTo(WEBHOOK_AT);
    }
}
