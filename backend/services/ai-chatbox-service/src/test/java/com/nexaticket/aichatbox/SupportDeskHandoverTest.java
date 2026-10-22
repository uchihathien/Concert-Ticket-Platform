// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportDeskAccess;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.HandoverNote;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.interfaces.rest.SupportDeskController;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import com.nexaticket.platform.web.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nhả và chuyển phiếu bị bỏ rơi (R-02) — controller gọi trực tiếp trên use case thật và kho trong bộ
 * nhớ. Nằm ở package gốc vì ArchUnit quét cả test và lớp {@code interfaces} không được thấy kiểu
 * domain — mà test phải dựng phiếu bằng chúng. Phần SQL ({@code details}, tin AGENT, không đóng phiếu vừa nhả) ở StaleHandoffReleaseIT.
 */
class SupportDeskHandoverTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private final UUID actor = UUID.randomUUID();
    private final UUID holder = UUID.randomUUID();
    private final UUID knownAgent = UUID.randomUUID();
    private final Set<UUID> existingAccounts = Set.of(actor, holder, knownAgent);
    private final SupportDeskAccess access = mock(SupportDeskAccess.class);

    private InMemoryHandoffRepository repository = new InMemoryHandoffRepository();
    private SupportDeskController controller;

    @BeforeEach
    void setUp() {
        when(access.requireSupportAgent()).thenReturn(actor);
        controller = controllerOver(repository);
    }

    private SupportDeskController controllerOver(InMemoryHandoffRepository repo) {
        HandoffUseCase handoffs = new HandoffUseCase(
                repo,
                new InMemoryChatHistory(),
                id -> existingAccounts.contains(id) ? Optional.of("Người trực") : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AgentMetrics(new SimpleMeterRegistry()));
        return new SupportDeskController(handoffs, access);
    }

    private Handoff waiting() {
        Handoff h = Handoff.request(
                UUID.randomUUID(),
                UUID.randomUUID(),
                HandoffTrigger.CUSTOMER_REQUEST,
                SupportIntent.GENERAL,
                "Khách cần người thật",
                null,
                "cho tôi gặp nhân viên",
                NOW.minusSeconds(3600));
        return repository.openOrExisting(h);
    }

    private Handoff assignedTo(UUID agent) {
        Handoff h = waiting();
        return repository.claim(h.id(), agent, NOW.minusSeconds(1800)).orElseThrow();
    }

    private static void assertCode(Runnable call, AiChatboxErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(code));
    }

    @Test
    void nguoi_khac_nha_phieu_dang_giu_ve_hang_doi() {
        Handoff h = assignedTo(holder);

        var row = controller.release(h.id(), new SupportDeskController.ReleaseRequest("Hết ca"));

        assertThat(row.status()).isEqualTo("WAITING");
        Handoff after = repository.findById(h.id()).orElseThrow();
        assertThat(after.assignedAgentId()).isNull();
        assertThat(after.assignedAt()).isNull();
        // Nhả xong thì người khác nhận lại được.
        assertThat(repository.claim(h.id(), knownAgent, NOW)).isPresent();
    }

    @Test
    void nha_phieu_dang_cho_la_idempotent_va_body_tuy_chon() {
        Handoff h = waiting();

        var row = controller.release(h.id(), null);

        assertThat(row.status()).isEqualTo("WAITING");
    }

    @Test
    void nha_hoac_chuyen_phieu_da_dong_bi_tu_choi() {
        Handoff h = assignedTo(holder);
        repository.transition(h.id(), HandoffStatus.ASSIGNED, holder, h.resolve(NOW));

        assertCode(() -> controller.release(h.id(), null), AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED);
        assertCode(
                () -> controller.reassign(h.id(), new SupportDeskController.ReassignRequest(knownAgent, null)),
                AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED);
    }

    @Test
    void chuyen_phieu_cho_nguoi_khac() {
        Handoff h = assignedTo(holder);

        var row =
                controller.reassign(h.id(), new SupportDeskController.ReassignRequest(knownAgent, "Chuyên hoàn tiền"));

        assertThat(row.status()).isEqualTo("ASSIGNED");
        Handoff after = repository.findById(h.id()).orElseThrow();
        assertThat(after.assignedAgentId()).isEqualTo(knownAgent);
        assertThat(after.assignedAt()).isEqualTo(NOW);
    }

    @Test
    void chuyen_phieu_dang_cho_cung_duoc() {
        Handoff h = waiting();

        controller.reassign(h.id(), new SupportDeskController.ReassignRequest(knownAgent, null));

        assertThat(repository.findById(h.id()).orElseThrow().assignedAgentId()).isEqualTo(knownAgent);
    }

    @Test
    void chuyen_cho_chinh_nguoi_dang_giu_tra_nguyen() {
        Handoff h = assignedTo(holder);

        controller.reassign(h.id(), new SupportDeskController.ReassignRequest(holder, null));

        assertThat(repository.findById(h.id()).orElseThrow().assignedAt()).isEqualTo(NOW.minusSeconds(1800));
    }

    @Test
    void chuyen_cho_tai_khoan_khong_ton_tai_tra_422() {
        Handoff h = assignedTo(holder);

        assertCode(
                () -> controller.reassign(h.id(), new SupportDeskController.ReassignRequest(UUID.randomUUID(), null)),
                AiChatboxErrorCode.HANDOFF_TARGET_AGENT_NOT_FOUND);
        assertThat(repository.findById(h.id()).orElseThrow().assignedAgentId()).isEqualTo(holder);
    }

    /** Người khác nhận phiếu xen giữa lúc đọc và lúc nhả → 409 kèm trạng thái mới, không ghi đè. */
    @Test
    void thua_race_khi_nha_tra_409_state_changed() {
        UUID sneaky = UUID.randomUUID();
        InMemoryHandoffRepository racing = new InMemoryHandoffRepository() {
            @Override
            public synchronized boolean handover(
                    UUID handoffId,
                    HandoffStatus expectedStatus,
                    UUID expectedAgent,
                    UUID newAgent,
                    Instant now,
                    HandoverNote note) {
                // Người khác chuyển phiếu cho mình ngay trước lệnh ghi này.
                super.handover(handoffId, expectedStatus, expectedAgent, sneaky, now, note);
                return super.handover(handoffId, expectedStatus, expectedAgent, newAgent, now, note);
            }
        };
        repository = racing;
        controller = controllerOver(racing);
        Handoff h = assignedTo(holder);

        assertThatThrownBy(() -> controller.release(h.id(), null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(AiChatboxErrorCode.HANDOFF_STATE_CHANGED);
            assertThat(e.meta()).containsEntry("assignedAgentId", sneaky);
        });
        assertThat(racing.findById(h.id()).orElseThrow().assignedAgentId()).isEqualTo(sneaky);
    }
}
