// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** JSON trả cho khách không mang dữ liệu nội bộ của bàn hỗ trợ (R-09). */
class CustomerHandoffViewJsonTest {

    private static final List<String> INTERNAL_KEYS = List.of(
            "reason", "details", "trigger", "intent", "assignedAgentId", "customerEmail", "customerId", "lastQuestion");

    @Test
    void chat_thread_chi_co_trang_thai_moc_yeu_cau_va_ten_nguoi_ho_tro() throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        var thread = new HandoffViews.ChatThread(
                UUID.randomUUID(),
                new HandoffViews.CustomerHandoffView("ASSIGNED", Instant.parse("2026-10-22T10:00:00Z"), "Lan"),
                List.of());

        JsonNode handoff = json.readTree(json.writeValueAsString(thread)).path("handoff");

        assertThat(handoff.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("status", "requestedAt", "assignedAgentName");
        for (String key : INTERNAL_KEYS) {
            assertThat(handoff.has(key)).as(key).isFalse();
        }
        assertThat(handoff.path("assignedAgentName").asText()).isEqualTo("Lan");
    }
}
