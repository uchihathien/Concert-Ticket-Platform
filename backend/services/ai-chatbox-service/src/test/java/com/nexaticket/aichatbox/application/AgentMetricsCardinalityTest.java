// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AgentMetricsCardinalityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);

    /** Tên tool do mô hình sinh — tên bịa không được thành series mới. */
    @Test
    void ten_tool_la_gom_ve_unknown() {
        metrics.recordTool("dropAllTables", "failed");
        metrics.recordTool("tool_" + System.nanoTime(), "failed");
        metrics.recordTool(SupportAgentTools.FIND_EVENTS, "ok");

        assertThat(registry.find("aichatbox.tool.calls")
                        .tag("tool", "unknown")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(registry.find("aichatbox.tool.calls")
                        .tag("tool", SupportAgentTools.FIND_EVENTS)
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("aichatbox.tool.calls").counters()).hasSize(2);
    }

    @Test
    void dem_ca_lan_goi_llm_thanh_cong_lan_hong() {
        metrics.recordLlmCall(() -> "ok");
        metrics.recordLlmCall(() -> "ok");
        assertThatThrownBy(() -> metrics.recordLlmCall(() -> {
                    throw new IllegalStateException("hỏng");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find("aichatbox.llm.calls")
                        .tag("outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(registry.find("aichatbox.llm.calls")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
