// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import com.nexaticket.ledger.infrastructure.amqp.OrderPaidListener;
import com.nexaticket.ledger.support.LedgerTestBase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code order.paid} đi tới tận sổ cái thật: consumer → handler → PostgreSQL với role runtime.
 *
 * <p>Gọi thẳng {@link OrderPaidListener#onOrderPaid} thay vì qua RabbitMQ — profile test tắt listener
 * container, và thứ cần kiểm ở đây là bút toán nằm trong database chứ không phải broker.
 *
 * <p>Mọi đơn và tổ chức là UUID ngẫu nhiên cho từng lần chạy: container {@code withReuse(true)} giữ lại
 * bút toán của những lần trước, và khoá {@code order:<id>} trùng sẽ biến ca "ghi mới" thành ca "no-op".
 */
class OrderPaidListenerIT extends LedgerTestBase {

    @Autowired
    OrderPaidListener listener;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    JdbcTemplate jdbc;

    private static Message orderPaid(UUID orderId, UUID organizationId, long total, long commission) {
        String body =
                """
                {"orderId":"%s","organizationId":"%s","paidAt":"2026-09-09T03:00:00Z",
                 "totalVnd":%d,"commissionVnd":%d,"commissionBps":500}
                """
                        .formatted(orderId, organizationId, total, commission);
        MessageProperties props = new MessageProperties();
        props.setMessageId(UUID.randomUUID().toString());
        props.setHeader("eventType", "order.paid");
        props.setHeader("aggregateId", orderId.toString());
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private int entriesFor(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, "order:" + orderId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("order.paid ghi bút toán N1 với hoa hồng của chứng từ; sổ vẫn cân")
    void ghi_but_toan_tu_message() {
        UUID orderId = UUID.randomUUID();
        UUID org = UUID.randomUUID();

        listener.onOrderPaid(orderPaid(orderId, org, 999_999, 49_998));

        assertThat(entriesFor(orderId)).isEqualTo(1);
        UUID held = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);
        assertThat(ledger.balanceOf(held)).isEqualTo(Money.ofVnd(950_001));
        assertThat(ledger.trialBalance().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("Cùng một đơn tới bằng hai message (outbox gửi lại): chỉ một bút toán, số dư không nhân đôi")
    void message_gui_lai_chi_ghi_mot_lan() {
        UUID orderId = UUID.randomUUID();
        UUID org = UUID.randomUUID();

        listener.onOrderPaid(orderPaid(orderId, org, 2_000_000, 100_000));
        listener.onOrderPaid(orderPaid(orderId, org, 2_000_000, 100_000));

        assertThat(entriesFor(orderId)).isEqualTo(1);
        UUID held = ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org);
        assertThat(ledger.balanceOf(held)).isEqualTo(Money.ofVnd(1_900_000));
    }

    @Test
    @DisplayName("Message thiếu totalVnd: ack (không ném) và không ghi sổ — nếu ném sẽ chặn hàng đợi tuần tự")
    void thieu_tong_tien_khong_chan_hang_doi() {
        UUID orderId = UUID.randomUUID();
        String body = "{\"orderId\":\"%s\",\"organizationId\":\"%s\",\"paidAt\":\"2026-09-09T03:00:00Z\"}"
                .formatted(orderId, UUID.randomUUID());
        MessageProperties props = new MessageProperties();
        props.setMessageId(UUID.randomUUID().toString());
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

        assertThatCode(() -> listener.onOrderPaid(message)).doesNotThrowAnyException();
        assertThat(entriesFor(orderId)).isZero();
    }
}
