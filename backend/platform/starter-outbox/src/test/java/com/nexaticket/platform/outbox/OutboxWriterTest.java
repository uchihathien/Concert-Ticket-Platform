// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.id.CorrelationContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link OutboxWriter}: ghi integration event vào outbox trong CÙNG transaction với thay đổi nghiệp
 * vụ (ADR-0005).
 *
 * <p>Điều quan trọng nhất là lời từ chối khi không có transaction: ghi ngoài transaction nghĩa là
 * event có thể được publish cho một thay đổi đã rollback.
 */
class OutboxWriterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboxWriter writer = new OutboxWriter(jdbc, new ObjectMapper());

    private static final UUID ORDER = UUID.fromString("dddddddd-0000-4000-a000-000000000004");

    @AfterEach
    void don_dep() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        CorrelationContext.clear();
    }

    record OrderPaid(String orderId, long amountVnd, String note) {}

    @Test
    @DisplayName("ngoài transaction: từ chối và không ghi gì")
    void ngoai_transaction_tu_choi() {
        assertThatThrownBy(() -> writer.append("nexaticket.ordering", "Order", ORDER, "order.paid", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("trong transaction: INSERT đủ cột, payload là JSON, correlationId lấy từ ngữ cảnh")
    void trong_transaction_ghi_du_cot() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        CorrelationContext.set("corr-outbox-1");

        writer.append(
                "nexaticket.ordering", "Order", ORDER, "order.paid", new OrderPaid("o-1", 1_500_000, "Vé hạng A"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc)
                .update(
                        sql.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture());
        assertThat(sql.getValue()).contains("INSERT INTO outbox").contains("?::jsonb");

        var values = args.getAllValues();
        assertThat(values.get(0)).isInstanceOf(UUID.class);
        assertThat(values.subList(1, 5)).containsExactly("Order", ORDER, "order.paid", "nexaticket.ordering");
        assertThat(new ObjectMapper().readTree((String) values.get(5)))
                .isEqualTo(new ObjectMapper()
                        .readTree("{\"orderId\":\"o-1\",\"amountVnd\":1500000,\"note\":\"Vé hạng A\"}"));
        assertThat(values.get(6)).isEqualTo("corr-outbox-1");
    }

    @Test
    @DisplayName("mỗi lần ghi một id mới — hai event của cùng aggregate không đè nhau")
    void moi_lan_ghi_mot_id_moi() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        writer.append("x", "Order", ORDER, "order.created", Map.of());
        writer.append("x", "Order", ORDER, "order.paid", Map.of());

        ArgumentCaptor<Object> id = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(
                        anyString(),
                        id.capture(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(id.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("payload không serialize được: IllegalArgumentException nêu event type, không ghi gì")
    void payload_khong_serialize_duoc() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };

        assertThatThrownBy(() -> writer.append("x", "Order", ORDER, "order.weird", unserializable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order.weird");
        verifyNoInteractions(jdbc);
    }
}
