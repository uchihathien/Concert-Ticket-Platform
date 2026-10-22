// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import com.nexaticket.aichatbox.application.handoff.SupportDeskAccess;
import com.nexaticket.aichatbox.support.WebSliceTestBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bàn hỗ trợ: chỉ người có {@code PLATFORM_SUPPORT_HANDLE} (hiện là SUPER_ADMIN) nhận, trả lời,
 * đóng phiếu và đọc hàng đợi. Chuỗi bảo mật và {@code SupportDeskAccess} đều là bản thật; chỉ
 * {@code HandoffUseCase} là giả.
 */
@WebMvcTest(controllers = SupportDeskController.class)
@Import(SupportDeskAccess.class)
class SupportDeskControllerTest extends WebSliceTestBase {

    private static final UUID HANDOFF = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID ORG = UUID.fromString("aaaaaaaa-0000-4000-a000-000000000001");

    @MockitoBean
    HandoffUseCase handoffs;

    private static HandoffViews.HandoffRow row() {
        return new HandoffViews.HandoffRow(
                HANDOFF,
                UUID.randomUUID(),
                "WAITING",
                "CUSTOMER_REQUEST",
                "REFUND",
                "khách xin hoàn vé",
                null,
                "cho tôi hoàn vé",
                UUID.randomUUID(),
                "An",
                "an@example.com",
                null,
                null,
                Instant.parse("2026-10-22T03:00:00Z"),
                null,
                null,
                12);
    }

    private static HandoffViews.HandoffThread thread() {
        return new HandoffViews.HandoffThread(
                row(),
                List.of(new HandoffViews.MessageRow("USER", "xin chào", Instant.parse("2026-10-22T03:00:00Z"))),
                List.of());
    }

    @org.junit.jupiter.api.BeforeEach
    void reset_mock() {
        org.mockito.Mockito.reset(handoffs);
    }

    private String agentToken(UUID userId) {
        return tokenFor(userId, Map.of(), true);
    }

    private String roleToken(Role role) {
        return tokenFor(UUID.randomUUID(), Map.of(TenantId.of(ORG), role), false);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        return mvc.perform(request);
    }

    private static MockHttpServletRequestBuilder jsonPost(String url, String body) {
        return post(url, HANDOFF).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    @DisplayName("Ai được vào bàn hỗ trợ")
    class Authorization {

        private List<MockHttpServletRequestBuilder> allEndpoints() {
            return List.of(
                    get("/v1/support/handoffs"),
                    get("/v1/support/handoffs/{id}", HANDOFF),
                    post("/v1/support/handoffs/{id}/claim", HANDOFF),
                    jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"xin chào\"}"),
                    post("/v1/support/handoffs/{id}/resolve", HANDOFF));
        }

        @Test
        @DisplayName("người ẩn danh: 401 ở mọi endpoint, không chạm use case")
        void an_danh() throws Exception {
            for (MockHttpServletRequestBuilder endpoint : allEndpoints()) {
                call(endpoint, null).andExpect(status().isUnauthorized());
            }
            verifyNoInteractions(handoffs);
        }

        @Test
        @DisplayName("khách hàng: 403 ở mọi endpoint — không đọc, nhận hay đóng được phiếu của ai")
        void khach_hang() throws Exception {
            String customer = customerToken(UUID.randomUUID());
            for (MockHttpServletRequestBuilder endpoint : allEndpoints()) {
                call(endpoint, customer).andExpect(status().isForbidden());
            }
            verifyNoInteractions(handoffs);
        }

        @Test
        @DisplayName("ORG_OWNER, ORG_ADMIN, EVENT_MANAGER, CHECKIN_STAFF: 403 — ban tổ chức không đọc chat của khách")
        void thanh_vien_to_chuc() throws Exception {
            for (Role role : List.of(Role.ORG_OWNER, Role.ORG_ADMIN, Role.EVENT_MANAGER, Role.CHECKIN_STAFF)) {
                String token = roleToken(role);
                for (MockHttpServletRequestBuilder endpoint : allEndpoints()) {
                    call(endpoint, token).andExpect(status().isForbidden());
                }
            }
            verifyNoInteractions(handoffs);
        }

        @Test
        @DisplayName("token hỏng: 401")
        void token_hong() throws Exception {
            call(get("/v1/support/handoffs"), "rac").andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 mang thân RFC 7807 với mã FORBIDDEN")
        void than_403() throws Exception {
            call(get("/v1/support/handoffs"), customerToken(UUID.randomUUID()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Nested
    @DisplayName("Hàng đợi")
    class Queue {

        @Test
        @DisplayName("không tham số lọc: đường hàng đợi mặc định (queue), cỡ trang 50, trang 0")
        void mac_dinh() throws Exception {
            when(handoffs.queue(any(), any(), anyInt(), anyInt())).thenReturn(List.of(row()));

            call(get("/v1/support/handoffs"), agentToken(UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(HANDOFF.toString()))
                    .andExpect(jsonPath("$[0].status").value("WAITING"));

            verify(handoffs).queue(isNull(), isNull(), eq(50), eq(0));
        }

        @Test
        @DisplayName("mine=true: lọc theo chính người trực lấy từ JWT")
        void phieu_cua_toi() throws Exception {
            UUID agent = UUID.randomUUID();
            when(handoffs.queue(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            call(get("/v1/support/handoffs").param("mine", "true"), agentToken(agent))
                    .andExpect(status().isOk());

            verify(handoffs).queue(eq(agent), isNull(), eq(50), eq(0));
        }

        @Test
        @DisplayName("có status hoặc q: chuyển sang tra cứu (search)")
        void tra_cuu() throws Exception {
            when(handoffs.search(any(), any(String.class), any(String.class), any(), anyInt(), anyInt()))
                    .thenReturn(List.of());

            call(
                            get("/v1/support/handoffs")
                                    .param("status", "RESOLVED")
                                    .param("intent", "REFUND")
                                    .param("q", "hoàn"),
                            agentToken(UUID.randomUUID()))
                    .andExpect(status().isOk());

            verify(handoffs).search(isNull(), eq("RESOLVED"), eq("REFUND"), eq("hoàn"), eq(50), eq(0));
            verify(handoffs, never()).queue(any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("size bị kẹp [1,100]; page âm thành 0; offset = page * size đã kẹp")
        void phan_trang() throws Exception {
            when(handoffs.queue(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
            String agent = agentToken(UUID.randomUUID());

            call(get("/v1/support/handoffs").param("size", "100000").param("page", "2"), agent);
            call(get("/v1/support/handoffs").param("size", "-4").param("page", "-9"), agent);

            verify(handoffs).queue(isNull(), isNull(), eq(100), eq(200));
            verify(handoffs).queue(isNull(), isNull(), eq(1), eq(0));
        }

        @Test
        @DisplayName("intent dài quá 40 ký tự hoặc q dài quá 200: 400, không chạm use case")
        void tham_so_qua_dai() throws Exception {
            String agent = agentToken(UUID.randomUUID());

            call(get("/v1/support/handoffs").param("intent", "x".repeat(41)), agent)
                    .andExpect(status().isBadRequest());
            call(get("/v1/support/handoffs").param("q", "x".repeat(201)).param("status", "OPEN"), agent)
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(handoffs);
        }

        @Test
        @DisplayName("size không phải số: 400, không phải 500")
        void size_sai_kieu() throws Exception {
            call(get("/v1/support/handoffs").param("size", "nhieu"), agentToken(UUID.randomUUID()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Nhận, trả lời, đóng phiếu")
    class Actions {

        @Test
        @DisplayName("claim: id người trực lấy từ JWT, trả cả hội thoại")
        void claim() throws Exception {
            UUID agent = UUID.randomUUID();
            when(handoffs.claim(eq(HANDOFF), eq(agent), anyInt())).thenReturn(thread());

            call(post("/v1/support/handoffs/{id}/claim", HANDOFF), agentToken(agent))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.handoff.id").value(HANDOFF.toString()))
                    .andExpect(jsonPath("$.messages[0].role").value("USER"));

            verify(handoffs).claim(HANDOFF, agent, 200);
        }

        @Test
        @DisplayName("claim khi người khác nhận trước: 409 HANDOFF_ALREADY_TAKEN")
        void claim_da_co_nguoi_nhan() throws Exception {
            when(handoffs.claim(any(), any(), anyInt()))
                    .thenThrow(new ApiException(AiChatboxErrorCode.HANDOFF_ALREADY_TAKEN, "taken"));

            call(post("/v1/support/handoffs/{id}/claim", HANDOFF), agentToken(UUID.randomUUID()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HANDOFF_ALREADY_TAKEN"));
        }

        @Test
        @DisplayName("phiếu không tồn tại: 404 HANDOFF_NOT_FOUND")
        void khong_tim_thay() throws Exception {
            when(handoffs.thread(any(), anyInt()))
                    .thenThrow(new ApiException(AiChatboxErrorCode.HANDOFF_NOT_FOUND, "nf"));

            call(get("/v1/support/handoffs/{id}", HANDOFF), agentToken(UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HANDOFF_NOT_FOUND"));
        }

        @Test
        @DisplayName("id phiếu không phải UUID: 400")
        void id_sai_kieu() throws Exception {
            call(get("/v1/support/handoffs/{id}", "khong-phai-uuid"), agentToken(UUID.randomUUID()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reply: ghi bằng id người trực từ JWT rồi trả lại hội thoại")
        void reply() throws Exception {
            UUID agent = UUID.randomUUID();
            when(handoffs.thread(eq(HANDOFF), anyInt())).thenReturn(thread());

            call(jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"Em hỗ trợ ạ\"}"), agentToken(agent))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages[0].content").value("xin chào"));

            verify(handoffs).reply(HANDOFF, agent, "Em hỗ trợ ạ");
        }

        @Test
        @DisplayName("reply: không tin agentId trong body — chỉ id từ JWT được dùng")
        void reply_bo_qua_agent_id_trong_body() throws Exception {
            UUID agent = UUID.randomUUID();
            UUID spoofed = UUID.randomUUID();
            when(handoffs.thread(eq(HANDOFF), anyInt())).thenReturn(thread());

            call(
                    jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"hi\",\"agentId\":\"" + spoofed + "\"}"),
                    agentToken(agent));

            verify(handoffs).reply(HANDOFF, agent, "hi");
            verify(handoffs, never()).reply(any(), eq(spoofed), anyString());
        }

        @Test
        @DisplayName("reply trống, toàn khoảng trắng, quá 4000 ký tự hoặc thiếu trường: 400, không ghi gì")
        void reply_khong_hop_le() throws Exception {
            String agent = agentToken(UUID.randomUUID());

            call(jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"\"}"), agent)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.meta.fields.text").exists());
            call(jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"   \"}"), agent)
                    .andExpect(status().isBadRequest());
            call(jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"" + "x".repeat(4001) + "\"}"), agent)
                    .andExpect(status().isBadRequest());
            call(jsonPost("/v1/support/handoffs/{id}/messages", "{}"), agent).andExpect(status().isBadRequest());
            call(jsonPost("/v1/support/handoffs/{id}/messages", "không phải json"), agent)
                    .andExpect(status().isBadRequest());

            verify(handoffs, never()).reply(any(), any(), anyString());
        }

        @Test
        @DisplayName("reply đúng 4000 ký tự là biên còn hợp lệ")
        void reply_dung_bien() throws Exception {
            when(handoffs.thread(eq(HANDOFF), anyInt())).thenReturn(thread());

            call(
                            jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"" + "x".repeat(4000) + "\"}"),
                            agentToken(UUID.randomUUID()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("reply vào phiếu chưa nhận: 409 HANDOFF_NOT_ASSIGNED")
        void reply_chua_nhan() throws Exception {
            org.mockito.Mockito.doThrow(new ApiException(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED, "x"))
                    .when(handoffs)
                    .reply(any(), any(), anyString());

            call(jsonPost("/v1/support/handoffs/{id}/messages", "{\"text\":\"hi\"}"), agentToken(UUID.randomUUID()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HANDOFF_NOT_ASSIGNED"));
        }

        @Test
        @DisplayName("resolve: id người trực từ JWT; phiếu đã đóng thì 409")
        void resolve() throws Exception {
            UUID agent = UUID.randomUUID();
            when(handoffs.resolve(HANDOFF, agent)).thenReturn(row());

            call(post("/v1/support/handoffs/{id}/resolve", HANDOFF), agentToken(agent))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(HANDOFF.toString()));

            when(handoffs.resolve(HANDOFF, agent))
                    .thenThrow(new ApiException(AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED, "closed"));
            call(post("/v1/support/handoffs/{id}/resolve", HANDOFF), agentToken(agent))
                    .andExpect(status().isConflict());
        }
    }
}
