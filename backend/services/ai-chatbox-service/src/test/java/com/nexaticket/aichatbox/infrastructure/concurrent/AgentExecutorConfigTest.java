// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.kernel.id.CorrelationContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Correlation id đi theo lượt chat sang luồng agent, và không sót sang lượt kế tiếp. */
class AgentExecutorConfigTest {

    private final AgentExecutorConfig.CorrelationTaskDecorator decorator =
            new AgentExecutorConfig.CorrelationTaskDecorator();

    @AfterEach
    void clean() {
        MDC.clear();
        CorrelationContext.clear();
    }

    @Test
    void mang_correlation_sang_luong_agent_roi_don_sach() throws Exception {
        MDC.put("correlationId", "abc");
        CorrelationContext.set("abc");
        AtomicReference<String> mdcSeen = new AtomicReference<>();
        AtomicReference<String> contextSeen = new AtomicReference<>();
        Runnable first = decorator.decorate(() -> {
            mdcSeen.set(MDC.get("correlationId"));
            contextSeen.set(CorrelationContext.current());
        });
        clean();

        // Cùng MỘT luồng chạy cả hai việc — đúng cảnh pool dùng lại luồng.
        AtomicReference<String> leftoverMdc = new AtomicReference<>("chưa chạy");
        AtomicReference<String> leftoverContext = new AtomicReference<>("chưa chạy");
        Runnable second = decorator.decorate(() -> {
            leftoverMdc.set(MDC.get("correlationId"));
            leftoverContext.set(CorrelationContext.current());
        });
        Thread worker = new Thread(() -> {
            first.run();
            second.run();
        });
        worker.start();
        worker.join();

        assertThat(mdcSeen.get()).isEqualTo("abc");
        assertThat(contextSeen.get()).isEqualTo("abc");
        assertThat(leftoverMdc.get()).isNull();
        assertThat(leftoverContext.get()).isEqualTo(CorrelationContext.UNKNOWN);
    }
}
