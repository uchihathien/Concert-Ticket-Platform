// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Chuyển trạng thái phiếu dưới tranh chấp thật — hai luồng, Postgres thật (R-01, R-03, R-04).
 *
 * <p>Lỗi gốc: {@code resolve} đọc phiếu rồi ghi đè mù. Nhân viên A bấm Đóng đúng lúc B bấm Nhận
 * thì lệnh của A xoá {@code assigned_agent_id} của B. Lặp 50 lần vì một race chỉ hiện ra khi hai
 * lệnh thật sự chồng lên nhau — một lần chạy xanh không chứng minh gì.
 */
class HandoffTransitionRaceIT extends AiChatboxTestBase {

    private static final int ROUNDS = 50;

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

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void resetModel() {
        llm.reset();
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private Handoff openHandoff() {
        UUID sessionId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, UUID.randomUUID(), "cho tôi gặp nhân viên");
        return handoffs.openFor(sessionId).orElseThrow();
    }

    /** Chạy hai việc cùng lúc, bắt đầu đúng một thời điểm; trả về kết quả hoặc ngoại lệ của từng việc. */
    private Object[] race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<Object> a = pool.submit(() -> {
            start.await();
            return outcomeOf(first);
        });
        Future<Object> b = pool.submit(() -> {
            start.await();
            return outcomeOf(second);
        });
        start.countDown();
        return new Object[] {a.get(), b.get()};
    }

    private static Object outcomeOf(Callable<Object> task) {
        try {
            return task.call();
        } catch (Exception e) {
            return e;
        }
    }

    private static boolean failedWith(Object outcome, AiChatboxErrorCode code) {
        return outcome instanceof ApiException e && e.errorCode() == code;
    }

    @Test
    void dong_va_nhan_cung_luc_dung_mot_ben_thang_va_khong_ai_bi_xoa_luot_nhan() throws Exception {
        int claimWon = 0;
        int resolveWon = 0;
        for (int i = 0; i < ROUNDS; i++) {
            UUID id = openHandoff().id();
            UUID agentA = UUID.randomUUID();
            UUID agentB = UUID.randomUUID();

            Object[] out = race(() -> handoffs.resolve(id, agentA), () -> handoffs.claim(id, agentB, 10));
            Handoff after = repository.findById(id).orElseThrow();

            if (after.status() == HandoffStatus.ASSIGNED) {
                claimWon++;
                // Lượt nhận của B còn nguyên — bên thua không được đặt nó về null.
                assertThat(after.assignedAgentId()).isEqualTo(agentB);
                assertThat(after.assignedAt()).isNotNull();
                // Nhận xong TRƯỚC khi resolve kịp đọc thì đó không còn là đua: resolve thấy phiếu của B
                // và từ chối như mọi lần (NOT_ASSIGNED). Đua thật — đọc WAITING rồi UPDATE hụt — thì
                // phải là STATE_CHANGED kèm trạng thái hiện tại.
                assertThat(failedWith(out[0], AiChatboxErrorCode.HANDOFF_STATE_CHANGED)
                                || failedWith(out[0], AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED))
                        .as("resolve thua phải nhận STATE_CHANGED hoặc NOT_ASSIGNED, nhận %s", out[0])
                        .isTrue();
                if (failedWith(out[0], AiChatboxErrorCode.HANDOFF_STATE_CHANGED)) {
                    assertThat(((ApiException) out[0]).meta())
                            .containsEntry("status", "ASSIGNED")
                            .containsEntry("assignedAgentId", agentB);
                }
            } else {
                resolveWon++;
                assertThat(after.status()).isEqualTo(HandoffStatus.RESOLVED);
                assertThat(after.assignedAgentId()).isNull();
                assertThat(failedWith(out[1], AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED))
                        .as("claim thua phải nhận HANDOFF_ALREADY_RESOLVED, nhận %s", out[1])
                        .isTrue();
            }
        }
        assertThat(claimWon + resolveWon).isEqualTo(ROUNDS);
    }

    /** Đóng hai lần trả lại chính phiếu đó và không dịch {@code resolved_at}. */
    @Test
    void dong_phieu_da_dong_tra_lai_chinh_no() {
        UUID id = openHandoff().id();
        UUID agentA = UUID.randomUUID();

        handoffs.resolve(id, agentA);
        var resolvedAt = repository.findById(id).orElseThrow().resolvedAt();
        var again = handoffs.resolve(id, agentA);

        assertThat(again.id()).isEqualTo(id);
        assertThat(repository.findById(id).orElseThrow().resolvedAt()).isEqualTo(resolvedAt);
    }

    /**
     * Trả lời và đóng cùng lúc: hoặc tin nhắn được ghi (khi {@code reply} giữ khoá trước), hoặc
     * {@code reply} bị từ chối và KHÔNG có dòng AGENT nào — không có kết cục thứ ba.
     */
    @Test
    void tra_loi_va_dong_cung_luc_khong_de_lot_tin_sau_khi_dong() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Handoff opened = openHandoff();
            UUID agentA = UUID.randomUUID();
            handoffs.claim(opened.id(), agentA, 10);

            Object[] out = race(
                    () -> {
                        handoffs.reply(opened.id(), agentA, "Chào bạn, mình xem giúp nhé");
                        return "replied";
                    },
                    () -> handoffs.resolve(opened.id(), agentA));

            long agentLines = history.transcript(opened.sessionId(), opened.userId(), 50).stream()
                    .filter(m -> m.role() == ChatRole.AGENT)
                    .count();
            assertThat(repository.findById(opened.id()).orElseThrow().status()).isEqualTo(HandoffStatus.RESOLVED);
            if ("replied".equals(out[0])) {
                assertThat(agentLines).isEqualTo(1);
            } else {
                assertThat(failedWith(out[0], AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED))
                        .as("reply thua phải nhận HANDOFF_ALREADY_RESOLVED, nhận %s", out[0])
                        .isTrue();
                assertThat(agentLines).isZero();
            }
        }
    }

    @Test
    void tra_loi_phieu_da_dong_bi_tu_choi() {
        Handoff opened = openHandoff();
        UUID agentA = UUID.randomUUID();
        handoffs.claim(opened.id(), agentA, 10);
        handoffs.resolve(opened.id(), agentA);

        assertThatThrownBy(() -> handoffs.reply(opened.id(), agentA, "muộn"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED));
    }

    /** Phiếu cũ vừa đóng thì "gặp nhân viên" mở phiếu mới, không 500. */
    @Test
    void phieu_cu_vua_dong_thi_mo_phieu_moi() {
        Handoff old = openHandoff();
        handoffs.resolve(old.id(), UUID.randomUUID());

        var fresh = handoffs.requestByCustomer(old.sessionId(), old.userId());

        assertThat(fresh.status()).isEqualTo(HandoffStatus.WAITING.name());
        assertThat(handoffs.openFor(old.sessionId()).orElseThrow().id()).isNotEqualTo(old.id());
    }
}
