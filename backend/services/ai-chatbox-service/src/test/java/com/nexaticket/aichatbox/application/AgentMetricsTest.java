// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Bốn series {@code aichatbox.proposals{status}} có mặt từ lúc khởi động, bằng 0, và chỉ job cập nhật (story 1.6). */
class AgentMetricsTest {

    @Test
    void bon_tag_trang_thai_ton_tai_voi_gia_tri_0_truoc_khi_job_chay() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AgentMetrics(registry);

        for (BookingProposalStatus status : BookingProposalStatus.values()) {
            assertThat(registry.get("aichatbox.proposals")
                            .tag("status", status.name())
                            .functionCounter()
                            .count())
                    .as(status.name())
                    .isZero();
        }
        assertThat(registry.find("aichatbox.proposals").functionCounters()).hasSize(4);
    }

    @Test
    void PROPOSED_la_tong_moi_trang_thai_con_lai_doc_dung_khoa() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.updateProposalTotals(Map.of(
                BookingProposalStatus.PROPOSED, 3L,
                BookingProposalStatus.CONFIRMED, 2L,
                BookingProposalStatus.EXPIRED, 1L));

        assertThat(registry.get("aichatbox.proposals")
                        .tag("status", "PROPOSED")
                        .functionCounter()
                        .count())
                .isEqualTo(6);
        assertThat(registry.get("aichatbox.proposals")
                        .tag("status", "CONFIRMED")
                        .functionCounter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get("aichatbox.proposals")
                        .tag("status", "EXPIRED")
                        .functionCounter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("aichatbox.proposals")
                        .tag("status", "CANCELLED")
                        .functionCounter()
                        .count())
                .isZero();
    }
}
