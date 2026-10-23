// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.SupportAgentPrompts;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort;
import com.nexaticket.aichatbox.domain.port.LlmUnavailableException;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryCustomerPreferences;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Số vòng ReAct được ghi ở MỌI đường kết thúc, và lỗi phía mô hình luôn thành phiếu người thật.
 *
 * <p>Trước đây lượt kết thúc sớm bằng tool điều khiển ({@code return} giữa vòng) không được ghi — phân
 * bố số vòng thiếu đúng những lượt đắt nhất.
 */
class AgentRoundsAndFallbackTest {

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemoryHandoffRepository handoffRepository = new InMemoryHandoffRepository();

    private CustomerSupportAgentUseCase build(LlmProviderPort llm) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
        AgentMetrics metrics = new AgentMetrics(registry);
        ObjectMapper json = new ObjectMapper();
        InMemoryChatHistory history = new InMemoryChatHistory();
        FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
        FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
        FakeCommerce.Knowledge knowledge = new FakeCommerce.Knowledge();
        FakeCommerce.Credentials credentials = new FakeCommerce.Credentials();
        HandoffUseCase handoffs =
                new HandoffUseCase(handoffRepository, history, id -> Optional.empty(), clock, metrics);
        SupportCaseUseCase cases = new SupportCaseUseCase(ordering, knowledge, credentials, handoffs, clock, json);
        BookingProposalUseCase booking =
                new BookingProposalUseCase(catalog, new InMemoryBookingProposalRepository(), clock);
        ToolDispatcher tools = new ToolDispatcher(
                ordering,
                catalog,
                knowledge,
                credentials,
                new FakeCommerce.Profiles(),
                booking,
                new CustomerPreferenceUseCase(new InMemoryCustomerPreferences(), java.time.Clock.systemUTC()),
                json,
                metrics);
        return new CustomerSupportAgentUseCase(
                history,
                new FakeAiProviders.FakeEmbeddings(),
                knowledge,
                llm,
                tools,
                handoffs,
                cases,
                new AgentProperties(null, 4, 10, 5, 0.55, 16, 32, 2),
                metrics,
                json);
    }

    private DistributionSummary rounds() {
        return registry.find("aichatbox.turn.rounds").summary();
    }

    @Test
    void ket_thuc_som_bang_tool_dieu_khien_van_ghi_so_vong() {
        FakeAiProviders.FakeLlm llm = new FakeAiProviders.FakeLlm();
        llm.willCallTool(SupportAgentTools.FIND_EVENTS, Map.of("query", "Trịnh"))
                .willCallTool(SupportAgentTools.ESCALATE_TO_HUMAN, Map.of("reason", "Khách cần người thật"));

        build(llm).executeAgentProcess(sessionId, userId, "Mình cần gặp người hỗ trợ về đêm nhạc Trịnh");

        assertThat(rounds().count()).isEqualTo(1);
        assertThat(rounds().totalAmount()).isEqualTo(2.0);
    }

    @Test
    void tra_loi_binh_thuong_ghi_dung_mot_lan() {
        FakeAiProviders.FakeLlm llm = new FakeAiProviders.FakeLlm().willAnswer("Chào bạn, mình giúp gì được?");

        build(llm).executeAgentProcess(sessionId, userId, "Chào");

        assertThat(rounds().count()).isEqualTo(1);
        assertThat(rounds().totalAmount()).isEqualTo(1.0);
    }

    /**
     * Adapter dịch mọi hỏng hóc khi đọc phản hồi thành {@code LlmUnavailableException}; use case biến
     * nó thành câu "đã chuyển cho nhân viên" kèm phiếu — không bao giờ 500 trống.
     */
    @Test
    void mo_hinh_hong_thi_chuyen_nhan_vien_va_van_ghi_so_vong() {
        LlmProviderPort broken = request -> {
            throw new LlmUnavailableException("Không đọc được phản hồi", new NullPointerException());
        };

        AgentReply reply = build(broken).executeAgentProcess(sessionId, userId, "Đơn của mình sao rồi?");

        assertThat(reply.answer()).isEqualTo(SupportAgentPrompts.assistantUnavailableMessage());
        assertThat(handoffRepository.openBySession(sessionId))
                .hasValueSatisfying(h -> assertThat(h.trigger()).isEqualTo(HandoffTrigger.ASSISTANT_UNAVAILABLE));
        assertThat(rounds().count()).isEqualTo(1);
    }
}
