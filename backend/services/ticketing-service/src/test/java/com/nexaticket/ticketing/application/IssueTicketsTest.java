// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.ticketing.application.command.IssueTicketsForOrderHandler;
import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import com.nexaticket.ticketing.domain.port.IdentityPort;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phát vé cho đơn đã thanh toán: chỉ đơn PAID, mỗi dòng đúng một vé, chạy lại không phát thêm.
 *
 * <p>Chốt chống phát trùng cuối cùng là unique index {@code order_item_id} — ở đây nó được giả bằng
 * giá trị trả về của {@link TicketRepository#issueAll}; {@code IssueOnOrderPaidIT} kiểm chính index đó.
 */
class IssueTicketsTest {

    private static final UUID ORDER = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ORG = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID BUYER = UUID.fromString("20000000-0000-4000-8000-000000000004");

    private TicketRepository tickets;
    private OrderingPort ordering;
    private IdentityPort identity;
    private IssueTicketsForOrderHandler forOrder;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        ordering = mock(OrderingPort.class);
        identity = mock(IdentityPort.class);
        forOrder = new IssueTicketsForOrderHandler(ordering, identity, new IssueTicketsHandler(tickets));
    }

    @Nested
    @DisplayName("Phát vé từ một lệnh")
    class TuMotLenh {

        @Test
        @DisplayName("Mỗi dòng của đơn thành đúng một vé VALID, mang nhãn snapshot và tên người mua")
        void moi_dong_mot_ve() {
            when(tickets.issueAll(anyList())).thenReturn(2);
            UUID item1 = UUID.randomUUID();
            UUID item2 = UUID.randomUUID();

            int issued = new IssueTicketsHandler(tickets)
                    .handle(new IssueTicketsHandler.Command(
                            ORDER,
                            SESSION,
                            ORG,
                            BUYER,
                            "Trần Thị Bình",
                            List.of(seat(item1, "A-1"), seat(item2, "A-2"))));

            assertThat(issued).isEqualTo(2);
            List<Ticket> sent = issuedTickets();
            assertThat(sent).extracting(Ticket::orderItemId).containsExactly(item1, item2);
            assertThat(sent).extracting(Ticket::seatCode).containsExactly("A-1", "A-2");
            assertThat(sent).allSatisfy(ticket -> {
                assertThat(ticket.status()).isEqualTo(TicketStatus.VALID);
                assertThat(ticket.orderId()).isEqualTo(ORDER);
                assertThat(ticket.eventSessionId()).isEqualTo(SESSION);
                assertThat(ticket.organizationId()).isEqualTo(ORG);
                assertThat(ticket.userId()).isEqualTo(BUYER);
                assertThat(ticket.holderName()).isEqualTo("Trần Thị Bình");
            });
        }

        @Test
        @DisplayName("Chạy lại một đơn đã phát: trả 0, không phải lỗi")
        void chay_lai_tra_khong() {
            when(tickets.issueAll(anyList())).thenReturn(0);

            int issued = new IssueTicketsHandler(tickets)
                    .handle(new IssueTicketsHandler.Command(
                            ORDER, SESSION, ORG, BUYER, null, List.of(seat(UUID.randomUUID(), "A-1"))));

            assertThat(issued).isZero();
        }
    }

    @Nested
    @DisplayName("Phát vé theo đơn hàng")
    class TheoDonHang {

        @Test
        @DisplayName("Đơn PAID: đọc tên người mua rồi phát vé cho đủ các dòng")
        void don_paid_phat_ve() {
            when(ordering.fetch(ORDER)).thenReturn(order("PAID", line("A-1"), line("A-2"), line("A-3")));
            when(identity.displayNameOf(BUYER)).thenReturn(Optional.of("Lê Văn Cường"));
            when(tickets.issueAll(anyList())).thenReturn(3);

            assertThat(forOrder.handle(ORDER)).isEqualTo(3);
            assertThat(issuedTickets()).hasSize(3).allSatisfy(ticket -> assertThat(ticket.holderName())
                    .isEqualTo("Lê Văn Cường"));
        }

        @Test
        @DisplayName("Đơn chưa PAID (message đến sớm, hoặc đã hoàn tiền): không phát vé, không hỏi identity")
        void don_chua_paid() {
            when(ordering.fetch(ORDER)).thenReturn(order("REFUNDED", line("A-1")));

            assertThat(forOrder.handle(ORDER)).isZero();
            verifyNoInteractions(identity, tickets);
        }

        @Test
        @DisplayName("Trạng thái so khớp chính xác: 'paid' viết thường không được coi là PAID")
        void trang_thai_so_khop_chinh_xac() {
            when(ordering.fetch(ORDER)).thenReturn(order("paid", line("A-1")));

            assertThat(forOrder.handle(ORDER)).isZero();
            verify(tickets, never()).issueAll(any());
        }

        @Test
        @DisplayName("Đơn PAID mà không có dòng nào: không phát vé")
        void don_khong_co_dong() {
            when(ordering.fetch(ORDER)).thenReturn(order("PAID"));

            assertThat(forOrder.handle(ORDER)).isZero();
            verifyNoInteractions(identity, tickets);
        }

        @Test
        @DisplayName("Identity im lặng: vẫn phát vé, chỉ thiếu tên — khách đã trả tiền")
        void identity_im_lang_van_phat() {
            when(ordering.fetch(ORDER)).thenReturn(order("PAID", line("A-1")));
            when(identity.displayNameOf(BUYER)).thenReturn(Optional.empty());
            when(tickets.issueAll(anyList())).thenReturn(1);

            assertThat(forOrder.handle(ORDER)).isEqualTo(1);
            assertThat(issuedTickets().get(0).holderName()).isNull();
        }

        @Test
        @DisplayName("Ordering không trả lời: ném lên để message được giao lại, không phát gì")
        void ordering_tam_thoi_hong() {
            when(ordering.fetch(ORDER)).thenThrow(new OrderingPort.OrderingUnavailableException("timeout", null));

            assertThatThrownBy(() -> forOrder.handle(ORDER))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
            verifyNoInteractions(tickets);
        }
    }

    private List<Ticket> issuedTickets() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Ticket>> captor = ArgumentCaptor.forClass(List.class);
        verify(tickets).issueAll(captor.capture());
        return captor.getValue();
    }

    private static IssueTicketsHandler.Command.SeatLine seat(UUID itemId, String code) {
        return new IssueTicketsHandler.Command.SeatLine(itemId, UUID.randomUUID(), code, "A", "SEATED", code, "VIP");
    }

    private static OrderingPort.Line line(String code) {
        return new OrderingPort.Line(UUID.randomUUID(), UUID.randomUUID(), code, "A", "SEATED", code, "VIP");
    }

    private static OrderingPort.PaidOrder order(String status, OrderingPort.Line... lines) {
        return new OrderingPort.PaidOrder(ORDER, SESSION, ORG, BUYER, status, List.of(lines));
    }
}
