// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ticketing.application.command.IssueTicketsForOrderHandler;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Phát vé khi nhận {@code order.paid}.
 *
 * <p>Ordering được thay bằng hàng giả điều khiển được: thứ cần kiểm ở đây là <b>hành vi khi đơn ở
 * trạng thái bất thường</b> và khi Ordering không đọc được — chứ không phải khả năng gọi HTTP.
 */
@Import(IssueOnOrderPaidIT.FakeOrdering.class)
class IssueOnOrderPaidIT extends TicketingTestBase {

    @TestConfiguration
    static class FakeOrdering {

        @Bean
        @Primary
        Fake fakeOrdering() {
            return new Fake();
        }

        static class Fake implements OrderingPort {

            UUID eventSessionId = UUID.randomUUID();
            UUID organizationId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            String status = "PAID";
            int lineCount = 2;
            boolean unavailable;

            /** Giữ nguyên giữa hai lần gọi để kiểm chống phát hành trùng. */
            final List<Line> lines = new ArrayList<>();

            @Override
            public PaidOrder fetch(UUID orderId) {
                if (unavailable) {
                    throw new OrderingUnavailableException("giả lập ordering hỏng", null);
                }
                if (lines.isEmpty()) {
                    for (int i = 1; i <= lineCount; i++) {
                        lines.add(new Line(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "A-" + i,
                                "A",
                                "SEATED",
                                String.valueOf(i),
                                "Ve ngoi"));
                    }
                }
                return new PaidOrder(orderId, eventSessionId, organizationId, userId, status, List.copyOf(lines));
            }
        }
    }

    @Autowired
    IssueTicketsForOrderHandler issueForOrder;

    @Autowired
    TicketRepository tickets;

    @Autowired
    FakeOrdering.Fake ordering;

    @BeforeEach
    void resetFake() {
        ordering.status = "PAID";
        ordering.unavailable = false;
        ordering.lineCount = 2;
        ordering.lines.clear();
    }

    @Test
    @DisplayName("Đơn đã thanh toán: phát đúng một vé cho mỗi dòng đơn")
    void phat_ve_cho_don_da_thanh_toan() {
        UUID orderId = UUID.randomUUID();

        assertThat(issueForOrder.handle(orderId)).isEqualTo(2);
        assertThat(tickets.findByOrder(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("Sự kiện đến hai lần: lần hai không phát thêm vé nào")
    void su_kien_den_hai_lan() {
        // Một ghế in ra hai mã QR nghĩa là hai người cùng tới cửa với vé hợp lệ. Chốt chặn ở
        // đây là tickets.order_item_id UNIQUE, không phải code.
        UUID orderId = UUID.randomUUID();

        assertThat(issueForOrder.handle(orderId)).isEqualTo(2);
        assertThat(issueForOrder.handle(orderId)).isZero();
        assertThat(tickets.findByOrder(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("Đơn đã hoàn tiền: KHÔNG phát vé, dù sự kiện tên là order.paid")
    void don_da_hoan_tien_thi_khong_phat() {
        // Sự kiện là thứ đã xảy ra LÚC PHÁT; Ordering là nguồn chân lý LÚC NÀY. Một message
        // đến trễ sau khi đơn đã hoàn tiền không được biến thành vé.
        ordering.status = "REFUNDED";
        UUID orderId = UUID.randomUUID();

        assertThat(issueForOrder.handle(orderId)).isZero();
        assertThat(tickets.findByOrder(orderId)).isEmpty();
    }

    @Test
    @DisplayName("Đơn chưa thanh toán: không phát vé")
    void don_chua_thanh_toan() {
        ordering.status = "AWAITING_PAYMENT";

        assertThat(issueForOrder.handle(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("Đơn không có dòng nào: không phát vé và không nổ")
    void don_rong() {
        ordering.lineCount = 0;

        assertThat(issueForOrder.handle(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("Ordering không đọc được: ném ra để message được giao lại")
    void ordering_hong_thi_nem() {
        // Nuốt lỗi ở đây sẽ để lại một đơn đã trả tiền mà mãi không có vé, và message thì đã
        // bị ack nên không ai giao lại nữa.
        ordering.unavailable = true;

        assertThatThrownBy(() -> issueForOrder.handle(UUID.randomUUID()))
                .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
    }
}
