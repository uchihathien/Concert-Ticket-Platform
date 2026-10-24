// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.booking.ChatBookingFlow;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingDraftRepository;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryCustomerPreferences;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lượt chat đặt vé đi thẳng vào luồng chip, không tốn một lượt mô hình nào; câu khác vẫn tới mô hình. */
class AgentBookingFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID eventSessionId = UUID.randomUUID();

    private final FakeAiProviders.FakeLlm llm = new FakeAiProviders.FakeLlm();
    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final InMemoryChatHistory history = new InMemoryChatHistory();
    private final CustomerSupportAgentUseCase agent = build();

    private CustomerSupportAgentUseCase build() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
        ObjectMapper json = new ObjectMapper();
        FakeCommerce.Credentials credentials = new FakeCommerce.Credentials();
        FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
        FakeCommerce.Knowledge knowledge = new FakeCommerce.Knowledge();
        InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();
        InMemoryBookingDraftRepository drafts = new InMemoryBookingDraftRepository();
        HandoffUseCase handoffs =
                new HandoffUseCase(new InMemoryHandoffRepository(), history, id -> Optional.empty(), clock, metrics);
        SupportCaseUseCase cases = new SupportCaseUseCase(ordering, knowledge, credentials, handoffs, clock, json);
        BookingProposalUseCase booking = new BookingProposalUseCase(catalog, proposals, clock, null, null, drafts);
        ToolDispatcher tools = new ToolDispatcher(
                ordering,
                catalog,
                knowledge,
                credentials,
                new FakeCommerce.Profiles(),
                booking,
                new CustomerPreferenceUseCase(new InMemoryCustomerPreferences(), clock),
                json,
                metrics);
        ChatBookingFlow flow =
                new ChatBookingFlow(catalog, id -> Map.of(), booking, proposals, drafts, ordering, credentials, clock);
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
                json,
                flow);
    }

    @Test
    void cau_dat_ve_ra_chip_ngay_khong_hoi_mo_hinh() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "cho mình 2 vé đêm nhạc Trịnh");

        assertThat(llm.calls).hasValue(0);
        ActionCard.Choices chips = (ActionCard.Choices) reply.action();
        assertThat(chips.step()).isEqualTo("ZONE");
        assertThat(chips.options()).extracting(ActionCard.Option::label).containsExactly("VIP", "Phổ thông");
        // Lượt được ghi vào hội thoại như mọi lượt khác — mô hình đọc lại nó ở lượt sau.
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(m -> m.role())
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);

        AgentReply proposed = agent.executeAgentProcess(
                sessionId, userId, chips.options().get(0).value());
        assertThat(proposed.action()).isInstanceOf(ActionCard.BookingProposal.class);
        assertThat(llm.calls).hasValue(0);
    }

    @Test
    void cau_khong_phai_dat_ve_van_toi_mo_hinh() {
        llm.willAnswer("Giữ chỗ 10 phút bạn nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "giữ chỗ được bao lâu?");

        assertThat(llm.calls).hasValue(1);
        assertThat(reply.action()).isNull();
    }
}
