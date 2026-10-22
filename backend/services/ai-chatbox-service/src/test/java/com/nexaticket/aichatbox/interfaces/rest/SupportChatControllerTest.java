// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.TurnAdmission;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.platform.security.tenant.ChatTenantFixture;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Đường idempotency và hạn chờ hàng đợi của {@code POST /support} — gọi controller trực tiếp, pool
 * chạy đồng bộ.
 */
class SupportChatControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final CustomerSupportAgentUseCase agent = mock(CustomerSupportAgentUseCase.class);
    private final ChatIdempotency idempotency = mock(ChatIdempotency.class);
    private final TurnAdmission admission = new TurnAdmission(new AgentProperties(null, 4, 10, 5, 0.55, 16, 32, 2));

    @BeforeEach
    void login() {
        ChatTenantFixture.customer(userId);
    }

    @AfterEach
    void logout() {
        ChatTenantFixture.clear();
    }

    private SupportChatController controller(Executor executor, Clock clock) {
        return new SupportChatController(
                agent, mock(HandoffUseCase.class), admission, executor, idempotency, Duration.ofSeconds(60), clock);
    }

    private SupportChatController controller() {
        return controller(Runnable::run, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw (RuntimeException) e.getCause();
        }
    }

    /**
     * R-15: client gửi lại câu đầu tiên (không có sessionId) với cùng khoá. Bản lưu được trả lại
     * nguyên vẹn, kể cả sessionId mà lần đầu đã sinh, và mô hình không được gọi lần nữa.
     */
    @Test
    void gui_lai_cau_dau_tien_tra_dung_ban_da_luu() {
        UUID firstSession = UUID.randomUUID();
        var saved = new SupportChatController.AskResponse(firstSession, "Chào bạn", List.of());
        when(idempotency.beginOrReplay(eq(userId), eq("k1"), eq(null), eq("Xin chào")))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));
        when(agent.executeAgentProcess(any(), eq(userId), eq("Xin chào")))
                .thenReturn(new AgentReply(firstSession, "Chào bạn", List.of()));

        var request = new SupportChatController.AskRequest(null, "Xin chào");
        var first = join(controller().ask("k1", request));
        var second = join(controller().ask("k1", request));

        assertThat(second).isEqualTo(saved);
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        verify(agent, org.mockito.Mockito.times(1)).executeAgentProcess(any(), any(), any());
        // Chữ ký tính trên sessionId CLIENT gửi — null — không trên UUID vừa sinh.
        verify(idempotency, org.mockito.Mockito.times(2)).beginOrReplay(userId, "k1", null, "Xin chào");
    }

    /** `complete` hỏng thì khoá phải được nhả — không còn đường nào kẹt ở "đang xử lý". */
    @Test
    void luu_cau_tra_loi_hong_thi_nha_khoa_va_tra_503() {
        when(idempotency.beginOrReplay(any(), anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(agent.executeAgentProcess(any(), any(), any()))
                .thenReturn(new AgentReply(UUID.randomUUID(), "Chào bạn", List.of()));
        doThrow(new IllegalStateException("DB chập")).when(idempotency).complete(any(), anyString(), any());

        var future = controller().ask("k2", new SupportChatController.AskRequest(null, "Xin chào"));

        assertThatThrownBy(() -> join(future)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(AiChatboxErrorCode.ASSISTANT_BUSY));
        verify(idempotency).release(userId, "k2");
    }

    @Test
    void luot_hong_thi_nha_khoa_va_giu_nguyen_loi() {
        when(idempotency.beginOrReplay(any(), anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(agent.executeAgentProcess(any(), any(), any()))
                .thenThrow(new ApiException(AiChatboxErrorCode.CHAT_SESSION_NOT_FOUND, "Không tìm thấy phiên chat"));

        var future = controller().ask("k3", new SupportChatController.AskRequest(UUID.randomUUID(), "Xin chào"));

        assertThatThrownBy(() -> join(future)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(AiChatboxErrorCode.CHAT_SESSION_NOT_FOUND));
        verify(idempotency).release(userId, "k3");
        verify(idempotency, never()).complete(any(), anyString(), any());
    }

    /**
     * Lượt chờ hàng đợi quá hạn: 503 và mô hình không được gọi; suất đồng thời được trả lại để lượt
     * sau của chính người ấy không bị chặn.
     */
    @Test
    void cho_hang_doi_qua_han_thi_503_va_khong_goi_mo_hinh() {
        Clock[] clock = {Clock.fixed(T0, ZoneOffset.UTC)};
        Clock moving = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clock[0].instant();
            }
        };
        // Pool "chậm": việc chỉ chạy sau khi đồng hồ đã nhảy 61 giây.
        Executor late = task -> {
            clock[0] = Clock.fixed(T0.plusSeconds(61), ZoneOffset.UTC);
            task.run();
        };
        when(idempotency.beginOrReplay(any(), anyString(), any(), anyString())).thenReturn(Optional.empty());

        var future = controller(late, moving).ask("k4", new SupportChatController.AskRequest(null, "Xin chào"));

        assertThatThrownBy(() -> join(future)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(AiChatboxErrorCode.ASSISTANT_BUSY));
        verify(agent, never()).executeAgentProcess(any(), any(), any());
        verify(idempotency).release(userId, "k4");
        assertThat(admission.tryAcquire(userId)).isTrue();
        assertThat(admission.tryAcquire(userId)).isTrue();
    }
}
