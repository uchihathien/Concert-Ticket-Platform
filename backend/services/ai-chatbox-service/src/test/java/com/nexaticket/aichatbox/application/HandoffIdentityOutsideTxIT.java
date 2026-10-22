// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.domain.port.IdentityLookupPort;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Lời gọi sang identity nằm NGOÀI transaction (R-08) — trên Postgres thật, với một identity giả ghi
 * lại trạng thái transaction ở mỗi lần được gọi và có thể "treo" như identity thật khi chậm.
 */
@Import(HandoffIdentityOutsideTxIT.RecordingIdentityConfig.class)
class HandoffIdentityOutsideTxIT extends AiChatboxTestBase {

    @Autowired
    CustomerSupportAgentUseCase agent;

    @Autowired
    HandoffUseCase handoffs;

    @Autowired
    HandoffRepository repository;

    @Autowired
    ChatHistoryPort history;

    @Autowired
    FakeAiProviders.FakeLlm llm;

    @Autowired
    RecordingIdentity identity;

    @BeforeEach
    void reset() {
        llm.reset();
        identity.reset();
    }

    private Handoff openHandoff() {
        UUID sessionId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, UUID.randomUUID(), "cho tôi gặp nhân viên");
        return handoffs.openFor(sessionId).orElseThrow();
    }

    @Test
    void moi_duong_goi_identity_ngoai_transaction() {
        Handoff h = openHandoff();
        UUID agentId = UUID.randomUUID();

        handoffs.claim(h.id(), agentId, 10);
        handoffs.thread(h.id(), 10);
        handoffs.queue(null, null, 50, 0);
        handoffs.search(null, "OPEN", null, null, 50, 0);
        handoffs.customerThread(h.sessionId(), h.userId(), 10);
        handoffs.openRowFor(h.sessionId());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        history.append(session, user, ChatRole.USER, "chào");
        handoffs.requestByCustomer(session, user);
        handoffs.resolve(h.id(), agentId);

        assertThat(identity.calls).isNotEmpty();
        assertThat(identity.calledInsideTransaction)
                .as("số lời gọi identity bên trong transaction")
                .isZero();
    }

    /** Đường của khách không tra liên hệ của chính khách; chỉ tra tên người hỗ trợ khi đã có người nhận. */
    @Test
    void duong_cua_khach_khong_goi_contactOf() {
        Handoff h = openHandoff();
        identity.reset();

        handoffs.customerThread(h.sessionId(), h.userId(), 10);
        assertThat(identity.calls).isEmpty();

        UUID agentId = UUID.randomUUID();
        handoffs.claim(h.id(), agentId, 10);
        identity.reset();
        handoffs.customerThread(h.sessionId(), h.userId(), 10);

        assertThat(identity.calls).containsExactly("displayNameOf:" + agentId);
    }

    /**
     * Identity chậm 2 giây trong lúc {@code claim} dựng màn hình: người khác vẫn đóng được phiếu ngay
     * — {@code claim} không còn giữ khoá dòng trong lúc chờ HTTP.
     */
    @Test
    void claim_khong_giu_khoa_phieu_trong_luc_cho_identity() throws Exception {
        Handoff h = openHandoff();
        UUID agentA = UUID.randomUUID();
        identity.blockFor(agentA, Duration.ofSeconds(2));

        CompletableFuture<?> claiming = CompletableFuture.runAsync(() -> handoffs.claim(h.id(), agentA, 10));
        assertThat(identity.blocked.await(5, TimeUnit.SECONDS))
                .as("claim đã tới bước gọi identity")
                .isTrue();

        long started = System.nanoTime();
        handoffs.resolve(h.id(), agentA);
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        claiming.get(10, TimeUnit.SECONDS);

        assertThat(tookMillis).as("resolve không phải chờ claim nhả khoá").isLessThan(1500);
        assertThat(repository.findById(h.id()).orElseThrow().status()).isEqualTo(HandoffStatus.RESOLVED);
    }

    @TestConfiguration
    static class RecordingIdentityConfig {
        @Bean
        @Primary
        RecordingIdentity recordingIdentity() {
            return new RecordingIdentity();
        }
    }

    static class RecordingIdentity implements IdentityLookupPort {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile int calledInsideTransaction;
        volatile UUID slowFor;
        volatile Duration delay = Duration.ZERO;
        volatile CountDownLatch blocked = new CountDownLatch(1);

        void reset() {
            calls.clear();
            calledInsideTransaction = 0;
            slowFor = null;
            blocked = new CountDownLatch(1);
        }

        void blockFor(UUID agent, Duration howLong) {
            slowFor = agent;
            delay = howLong;
        }

        private void record(String call, UUID id) {
            calls.add(call + ":" + id);
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                calledInsideTransaction++;
            }
            if (id.equals(slowFor)) {
                // Chỉ treo MỘT lần — lời gọi đầu (của claim); các lời gọi sau trả ngay.
                slowFor = null;
                blocked.countDown();
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public Optional<String> displayNameOf(UUID userId) {
            record("displayNameOf", userId);
            return Optional.of("Người trực");
        }

        @Override
        public Optional<Contact> contactOf(UUID userId) {
            record("contactOf", userId);
            return Optional.of(new Contact("Khách", null));
        }
    }
}
