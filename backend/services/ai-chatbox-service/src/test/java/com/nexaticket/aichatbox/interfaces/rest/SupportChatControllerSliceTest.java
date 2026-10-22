// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.SessionTurnLock;
import com.nexaticket.aichatbox.application.agent.TurnAdmission;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.support.ChatSeeds;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import com.nexaticket.aichatbox.support.WebSliceTestBase;
import com.nexaticket.platform.web.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cửa chat của khách: danh tính chỉ lấy từ JWT, mỗi người một lượt đồng thời, khoá idempotency,
 * và <b>không ai đọc được hội thoại của người khác</b>.
 *
 * <p>Pool của agent được thay bằng executor chạy ngay trên luồng gọi (hoặc luôn từ chối) để test
 * tất định. {@code HandoffUseCase} là bản thật trên kho trong bộ nhớ — kiểm quyền sở hữu phiên nằm ở
 * đó, và mock nó đi là mock mất đúng thứ đang cần kiểm.
 */
@WebMvcTest(controllers = SupportChatController.class)
@Import(SupportChatControllerSliceTest.Wiring.class)
class SupportChatControllerSliceTest extends WebSliceTestBase {

    private static final UUID SESSION = UUID.fromString("44444444-4444-4444-a444-444444444444");
    private static final AtomicReference<Executor> EXECUTOR = new AtomicReference<>(Runnable::run);
    private static final InMemoryChatHistory HISTORY = new InMemoryChatHistory();

    @TestConfiguration
    static class Wiring {
        @Bean(name = "agentExecutor")
        Executor agentExecutor() {
            return command -> EXECUTOR.get().execute(command);
        }

        /** Epic 6 thêm khoá lượt theo phiên vào controller; lát cắt web không quét @Component. */
        @Bean
        SessionTurnLock sessionTurnLock() {
            return new SessionTurnLock();
        }

        @Bean
        HandoffUseCase handoffUseCase() {
            return new HandoffUseCase(
                    new InMemoryHandoffRepository(),
                    HISTORY,
                    id -> Optional.empty(),
                    Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC),
                    new AgentMetrics(new SimpleMeterRegistry()));
        }
    }

    @MockitoBean
    CustomerSupportAgentUseCase agent;

    @MockitoBean
    TurnAdmission admission;

    @MockitoBean
    ChatIdempotency idempotency;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void mac_dinh() {
        EXECUTOR.set(Runnable::run);
        org.mockito.Mockito.reset(agent, admission, idempotency);
        when(admission.tryAcquire(any())).thenReturn(true);
        when(idempotency.beginOrReplay(any(), anyString(), any(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void don_dep() {
        EXECUTOR.set(Runnable::run);
    }

    private static MockHttpServletRequestBuilder ask(String body) {
        return post("/v1/chat/agent/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        ResultActions first = mvc.perform(request);
        MvcResult result = first.andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(result));
        }
        return first;
    }

    @Nested
    @DisplayName("POST /v1/chat/agent/support")
    class Ask {

        @Test
        @DisplayName("người ẩn danh: 401, agent không chạy")
        void an_danh() throws Exception {
            mvc.perform(ask("{\"message\":\"xin chào\"}")).andExpect(status().isUnauthorized());

            verifyNoInteractions(agent, admission);
        }

        @Test
        @DisplayName("khách đã đăng nhập: agent chạy với userId LẤY TỪ JWT, trả câu trả lời và công cụ đã dùng")
        void khach_hoi() throws Exception {
            UUID user = UUID.randomUUID();
            String token = customerToken(user);
            when(agent.executeAgentProcess(eq(SESSION), eq(user), eq("đơn của tôi đâu")))
                    .thenReturn(new AgentReply(SESSION, "Đơn của bạn đã thanh toán", List.of("lookupOrder")));

            send(ask("{\"sessionId\":\"" + SESSION + "\",\"message\":\"đơn của tôi đâu\"}"), token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                    .andExpect(jsonPath("$.answer").value("Đơn của bạn đã thanh toán"))
                    .andExpect(jsonPath("$.toolsUsed[0]").value("lookupOrder"));

            verify(admission).tryAcquire(user);
            verify(admission).release(user);
        }

        @Test
        @DisplayName("userId nhét trong body bị bỏ qua — không đọc được đơn của người khác bằng cách khai id")
        void body_khong_doi_danh_tinh() throws Exception {
            UUID user = UUID.randomUUID();
            UUID victim = UUID.randomUUID();
            when(agent.executeAgentProcess(any(), any(), anyString()))
                    .thenReturn(new AgentReply(SESSION, "ok", List.of()));

            send(
                    ask("{\"sessionId\":\"" + SESSION + "\",\"message\":\"hi\",\"userId\":\"" + victim + "\"}"),
                    customerToken(user));

            verify(agent).executeAgentProcess(SESSION, user, "hi");
            verify(agent, never()).executeAgentProcess(any(), eq(victim), anyString());
        }

        @Test
        @DisplayName("không có sessionId: mở phiên mới (UUID ngẫu nhiên)")
        void mo_phien_moi() throws Exception {
            UUID user = UUID.randomUUID();
            when(agent.executeAgentProcess(any(), eq(user), anyString()))
                    .thenAnswer(inv -> new AgentReply(inv.getArgument(0), "ok", List.of()));

            send(ask("{\"message\":\"hi\"}"), customerToken(user))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").isNotEmpty());
        }

        @Test
        @DisplayName("message trống, toàn khoảng trắng, quá 2000 ký tự hoặc thiếu: 400, không giữ chỗ, không gọi agent")
        void message_khong_hop_le() throws Exception {
            String token = customerToken(UUID.randomUUID());

            for (String body : List.of(
                    "{\"message\":\"\"}",
                    "{\"message\":\"   \"}",
                    "{\"message\":\"" + "x".repeat(2001) + "\"}",
                    "{}",
                    "{\"sessionId\":\"abc\",\"message\":\"hi\"}")) {
                mvc.perform(ask(body).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                        .andExpect(status().isBadRequest());
            }

            verifyNoInteractions(agent, admission);
        }

        @Test
        @DisplayName("đang có lượt chạy của chính người này: 503 ASSISTANT_BUSY ngay lập tức, agent không chạy")
        void mot_nguoi_mot_luot() throws Exception {
            when(admission.tryAcquire(any())).thenReturn(false);

            mvc.perform(ask("{\"message\":\"hi\"}")
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(UUID.randomUUID()))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ASSISTANT_BUSY"));

            verifyNoInteractions(agent);
        }

        @Test
        @DisplayName("pool đầy (RejectedExecutionException): 503 ASSISTANT_BUSY và NHẢ chỗ đã giữ")
        void pool_day() throws Exception {
            UUID user = UUID.randomUUID();
            EXECUTOR.set(command -> {
                throw new RejectedExecutionException("full");
            });

            mvc.perform(ask("{\"message\":\"hi\"}").header(HttpHeaders.AUTHORIZATION, bearer(customerToken(user))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ASSISTANT_BUSY"));

            verify(admission).release(user);
        }

        @Test
        @DisplayName("agent ném lỗi: chỗ vẫn được nhả để khách hỏi lại")
        void agent_loi_van_nha_cho() throws Exception {
            UUID user = UUID.randomUUID();
            when(agent.executeAgentProcess(any(), any(), anyString()))
                    .thenThrow(new ApiException(AiChatboxErrorCode.ASSISTANT_UNAVAILABLE, "model down"));
            MvcResult first = mvc.perform(
                            ask("{\"message\":\"hi\"}").header(HttpHeaders.AUTHORIZATION, bearer(customerToken(user))))
                    .andReturn();

            if (first.getRequest().isAsyncStarted()) {
                mvc.perform(asyncDispatch(first)).andExpect(status().isServiceUnavailable());
            } else {
                assertThat(first.getResponse().getStatus()).isEqualTo(503);
            }

            verify(admission).release(user);
        }
    }

    @Nested
    @DisplayName("Idempotency-Key")
    class Idempotent {

        @Test
        @DisplayName("lượt chạy xong: lưu câu trả lời theo (userId, khoá)")
        void luu_ket_qua() throws Exception {
            UUID user = UUID.randomUUID();
            AgentReply reply = new AgentReply(SESSION, "xong", List.of());
            when(agent.executeAgentProcess(any(), any(), anyString())).thenReturn(reply);

            send(
                            ask("{\"sessionId\":\"" + SESSION + "\",\"message\":\"hi\"}")
                                    .header("Idempotency-Key", "k-1"),
                            customerToken(user))
                    .andExpect(status().isOk());

            verify(idempotency).beginOrReplay(user, "k-1", SESSION, "hi");
            verify(idempotency).complete(eq(user), eq("k-1"), any());
            verify(idempotency, never()).release(any(), anyString());
        }

        @Test
        @DisplayName("gửi lại cùng khoá: trả đúng bản đã lưu, không gọi agent, không giữ chỗ")
        void gui_lai_tra_ban_cu() throws Exception {
            UUID user = UUID.randomUUID();
            when(idempotency.beginOrReplay(any(), anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new SupportChatController.AskResponse(SESSION, "bản cũ", List.of())));

            send(
                            ask("{\"sessionId\":\"" + SESSION + "\",\"message\":\"hi\"}")
                                    .header("Idempotency-Key", "k-1"),
                            customerToken(user))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value("bản cũ"));

            verifyNoInteractions(agent, admission);
        }

        @Test
        @DisplayName("cùng khoá nhưng nội dung khác: 409 IDEMPOTENCY_KEY_REUSED từ ChatIdempotency")
        void khoa_dung_lai() throws Exception {
            when(idempotency.beginOrReplay(any(), anyString(), any(), anyString()))
                    .thenThrow(new ApiException(AiChatboxErrorCode.IDEMPOTENCY_KEY_REUSED, "reused"));

            mvc.perform(ask("{\"message\":\"khác\"}")
                            .header("Idempotency-Key", "k-1")
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(UUID.randomUUID()))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

            verifyNoInteractions(agent);
        }

        @Test
        @DisplayName("bị từ chối ở cửa giữ chỗ: nhả khoá để thử lại được ngay")
        void tu_choi_thi_nha_khoa() throws Exception {
            UUID user = UUID.randomUUID();
            when(admission.tryAcquire(any())).thenReturn(false);

            mvc.perform(ask("{\"message\":\"hi\"}")
                            .header("Idempotency-Key", "k-9")
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(user))))
                    .andExpect(status().isServiceUnavailable());

            verify(idempotency).release(user, "k-9");
            verify(idempotency, never()).complete(any(), anyString(), any());
        }

        @Test
        @DisplayName("agent hỏng: nhả khoá, không lưu bản trả lời lỗi")
        void agent_hong_nha_khoa() throws Exception {
            UUID user = UUID.randomUUID();
            when(agent.executeAgentProcess(any(), any(), anyString()))
                    .thenThrow(new ApiException(AiChatboxErrorCode.ASSISTANT_UNAVAILABLE, "down"));

            mvc.perform(ask("{\"message\":\"hi\"}")
                    .header("Idempotency-Key", "k-2")
                    .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(user))));

            verify(idempotency).release(user, "k-2");
            verify(idempotency, never()).complete(any(), anyString(), any());
        }

        @Test
        @DisplayName("khoá trống hoặc toàn khoảng trắng: coi như không có khoá")
        void khoa_trong() throws Exception {
            when(agent.executeAgentProcess(any(), any(), anyString()))
                    .thenReturn(new AgentReply(SESSION, "ok", List.of()));

            send(ask("{\"message\":\"hi\"}").header("Idempotency-Key", "  "), customerToken(UUID.randomUUID()))
                    .andExpect(status().isOk());

            verifyNoInteractions(idempotency);
        }
    }

    @Nested
    @DisplayName("GET /v1/chat/agent/sessions/{id}/messages")
    class Transcript {

        @Test
        @DisplayName("người ẩn danh: 401")
        void an_danh() throws Exception {
            mvc.perform(get("/v1/chat/agent/sessions/{id}/messages", SESSION)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("chủ phiên đọc được hội thoại của mình")
        void chu_phien_doc_duoc() throws Exception {
            UUID owner = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            ChatSeeds.userSaid(HISTORY, session, owner, "đơn NX-1 của tôi đâu");
            ChatSeeds.assistantSaid(HISTORY, session, owner, "Đơn NX-1 đã thanh toán");

            mvc.perform(get("/v1/chat/agent/sessions/{id}/messages", session)
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(owner))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(session.toString()))
                    .andExpect(jsonPath("$.messages.length()").value(2))
                    .andExpect(jsonPath("$.messages[0].role").value("USER"));
        }

        /**
         * Khách B đoán/biết được id phiên của khách A. Dữ liệu không rò ra (kho ném
         * {@code SessionNotOwnedException}), nhưng đó là {@code RuntimeException} thường, không phải
         * {@code ApiException}, và không có handler nào dịch nó — nên {@code GlobalExceptionHandler}
         * trả 500 kèm log ERROR có stack trace thay vì 404/403. Người dò id nhận được tín hiệu "phiên
         * này có thật" (500 khác với 200 rỗng của phiên không tồn tại) và bơm log lỗi vào chỗ vận
         * hành tìm sự cố thật. Test khẳng định hành vi đúng: lỗi của người gọi, không phải của server.
         */
        @Test
        @DisplayName("khách khác đọc phiên không phải của mình: lỗi 4xx (không phải 500) và không lộ nội dung")
        void khach_khac_khong_doc_duoc() throws Exception {
            UUID owner = UUID.randomUUID();
            UUID intruder = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            ChatSeeds.userSaid(HISTORY, session, owner, "mã đơn NX-SECRET, số tiền 9.900.000");

            MvcResult result = mvc.perform(get("/v1/chat/agent/sessions/{id}/messages", session)
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(intruder))))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isBetween(400, 499);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("NX-SECRET");
        }

        @Test
        @DisplayName("phiên chưa từng tồn tại: 200 với hội thoại rỗng, không phải 500")
        void phien_khong_ton_tai() throws Exception {
            mvc.perform(get("/v1/chat/agent/sessions/{id}/messages", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(UUID.randomUUID()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages").isEmpty());
        }

        @Test
        @DisplayName("id phiên không phải UUID: 400")
        void id_sai_kieu() throws Exception {
            mvc.perform(get("/v1/chat/agent/sessions/{id}/messages", "abc")
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(UUID.randomUUID()))))
                    .andExpect(status().isBadRequest());
        }
    }
}
