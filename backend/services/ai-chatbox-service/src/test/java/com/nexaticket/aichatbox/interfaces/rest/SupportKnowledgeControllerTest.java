// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.aichatbox.application.handoff.SupportDeskAccess;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeViews;
import com.nexaticket.aichatbox.support.WebSliceTestBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
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
 * Kho tri thức của trợ lý: chỉ nhân viên hỗ trợ (PLATFORM_SUPPORT_HANDLE) soạn được. Một khách
 * hàng sửa được kho là sửa được câu trả lời của trợ lý cho mọi khách khác.
 */
@WebMvcTest(controllers = SupportKnowledgeController.class)
@Import(SupportDeskAccess.class)
class SupportKnowledgeControllerTest extends WebSliceTestBase {

    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID CHUNK = UUID.fromString("33333333-3333-4333-a333-333333333333");
    private static final UUID ORG = UUID.fromString("aaaaaaaa-0000-4000-a000-000000000001");

    @MockitoBean
    KnowledgeBaseUseCase knowledge;

    @org.junit.jupiter.api.BeforeEach
    void reset_mock() {
        org.mockito.Mockito.reset(knowledge);
    }

    private String staff() {
        return tokenFor(UUID.randomUUID(), Map.of(), true);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        return mvc.perform(request);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final String RULES_BODY =
            "{\"eventTitle\":\"Đêm nhạc\",\"content\":\"Quy định\",\"refundAllowed\":true,\"refundWindowHours\":48,\"published\":true}";

    private List<MockHttpServletRequestBuilder> allEndpoints() {
        return List.of(
                json(post("/v1/support/knowledge/chunks"), "{\"title\":\"t\",\"content\":\"c\"}"),
                get("/v1/support/knowledge/chunks"),
                delete("/v1/support/knowledge/chunks/{id}", CHUNK),
                get("/v1/support/knowledge/preview").param("q", "hoàn vé"),
                json(put("/v1/support/knowledge/rules/{id}", EVENT), RULES_BODY),
                get("/v1/support/knowledge/rules/{id}", EVENT));
    }

    @Nested
    @DisplayName("Ai được sửa kho tri thức")
    class Authorization {

        @Test
        @DisplayName("người ẩn danh: 401 ở mọi endpoint")
        void an_danh() throws Exception {
            for (MockHttpServletRequestBuilder endpoint : allEndpoints()) {
                call(endpoint, null).andExpect(status().isUnauthorized());
            }
            verifyNoInteractions(knowledge);
        }

        @Test
        @DisplayName("khách hàng: 403 ở mọi endpoint, kho không bị đụng tới")
        void khach_hang() throws Exception {
            String customer = customerToken(UUID.randomUUID());
            for (MockHttpServletRequestBuilder endpoint : allEndpoints()) {
                call(endpoint, customer).andExpect(status().isForbidden());
            }
            verifyNoInteractions(knowledge);
        }

        @Test
        @DisplayName("mọi vai trò tổ chức (kể cả chủ sở hữu): 403")
        void vai_tro_to_chuc() throws Exception {
            for (Role role : List.of(Role.ORG_OWNER, Role.ORG_ADMIN, Role.EVENT_MANAGER, Role.CHECKIN_STAFF)) {
                String token = tokenFor(UUID.randomUUID(), Map.of(TenantId.of(ORG), role), false);
                for (MockHttpServletRequestBuilder endpoint : allEndpoints()) {
                    call(endpoint, token).andExpect(status().isForbidden());
                }
            }
            verifyNoInteractions(knowledge);
        }
    }

    @Nested
    @DisplayName("Đoạn tri thức")
    class Chunks {

        @Test
        @DisplayName("thêm đoạn: trả id mới cùng nội dung vừa gửi")
        void them_doan() throws Exception {
            when(knowledge.addChunk(any(), anyString(), anyString())).thenReturn(CHUNK);

            call(
                            json(
                                    post("/v1/support/knowledge/chunks"),
                                    "{\"eventId\":\"" + EVENT
                                            + "\",\"title\":\"Hoàn vé\",\"content\":\"Trong 48 giờ\"}"),
                            staff())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(CHUNK.toString()))
                    .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
                    .andExpect(jsonPath("$.title").value("Hoàn vé"));

            verify(knowledge).addChunk(EVENT, "Hoàn vé", "Trong 48 giờ");
        }

        @Test
        @DisplayName("tri thức chung (không có eventId): eventId null")
        void tri_thuc_chung() throws Exception {
            when(knowledge.addChunk(any(), anyString(), anyString())).thenReturn(CHUNK);

            call(json(post("/v1/support/knowledge/chunks"), "{\"title\":\"t\",\"content\":\"c\"}"), staff())
                    .andExpect(status().isOk());

            verify(knowledge).addChunk(isNull(), eq("t"), eq("c"));
        }

        @Test
        @DisplayName("thiếu/trống/quá dài: 400 kèm meta.fields, không gọi use case (title 200, content 8000)")
        void khong_hop_le() throws Exception {
            call(json(post("/v1/support/knowledge/chunks"), "{\"title\":\"\",\"content\":\"c\"}"), staff())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.title").exists());
            call(json(post("/v1/support/knowledge/chunks"), "{\"title\":\"t\",\"content\":\"  \"}"), staff())
                    .andExpect(status().isBadRequest());
            call(
                            json(
                                    post("/v1/support/knowledge/chunks"),
                                    "{\"title\":\"" + "t".repeat(201) + "\",\"content\":\"c\"}"),
                            staff())
                    .andExpect(status().isBadRequest());
            call(
                            json(
                                    post("/v1/support/knowledge/chunks"),
                                    "{\"title\":\"t\",\"content\":\"" + "c".repeat(8001) + "\"}"),
                            staff())
                    .andExpect(status().isBadRequest());
            call(
                            json(
                                    post("/v1/support/knowledge/chunks"),
                                    "{\"eventId\":\"abc\",\"title\":\"t\",\"content\":\"c\"}"),
                            staff())
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(knowledge);
        }

        @Test
        @DisplayName("liệt kê: cỡ trang kẹp [1,100], page âm thành 0, lọc theo eventId")
        void liet_ke() throws Exception {
            when(knowledge.listChunks(any(), anyInt(), anyInt()))
                    .thenReturn(List.of(new KnowledgeViews.ChunkRow(
                            CHUNK, EVENT, "t", "c", Instant.parse("2026-10-22T03:00:00Z"))));

            call(
                            get("/v1/support/knowledge/chunks")
                                    .param("eventId", EVENT.toString())
                                    .param("size", "99999")
                                    .param("page", "3"),
                            staff())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(CHUNK.toString()));
            call(get("/v1/support/knowledge/chunks").param("size", "0").param("page", "-1"), staff());

            verify(knowledge).listChunks(EVENT, 100, 300);
            verify(knowledge).listChunks(null, 1, 0);
        }

        @Test
        @DisplayName("xoá đoạn: gọi deleteChunk với đúng id; id sai kiểu thì 400")
        void xoa_doan() throws Exception {
            call(delete("/v1/support/knowledge/chunks/{id}", CHUNK), staff()).andExpect(status().isOk());
            call(delete("/v1/support/knowledge/chunks/{id}", "abc"), staff()).andExpect(status().isBadRequest());

            verify(knowledge).deleteChunk(CHUNK);
        }

        @Test
        @DisplayName("preview: trả các đoạn lấy ra kèm cờ used; q trống hoặc quá 2000 ký tự thì 400")
        void preview() throws Exception {
            when(knowledge.preview(any(), anyString()))
                    .thenReturn(List.of(new KnowledgeViews.RetrievedRow(CHUNK, null, "t", "c", 0.21, true)));

            call(get("/v1/support/knowledge/preview").param("q", "hoàn vé"), staff())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].used").value(true))
                    .andExpect(jsonPath("$[0].distance").value(0.21));
            call(get("/v1/support/knowledge/preview").param("q", " "), staff()).andExpect(status().isBadRequest());
            call(get("/v1/support/knowledge/preview").param("q", "x".repeat(2001)), staff())
                    .andExpect(status().isBadRequest());
            call(get("/v1/support/knowledge/preview"), staff()).andExpect(status().isBadRequest());

            verify(knowledge).preview(null, "hoàn vé");
        }
    }

    @Nested
    @DisplayName("Quy định sự kiện")
    class Rules {

        @Test
        @DisplayName("ghi đè quy định: chuyển nguyên các giá trị thô xuống use case")
        void upsert() throws Exception {
            when(knowledge.upsertRules(any(), anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean()))
                    .thenReturn(new KnowledgeViews.RulesRow(
                            EVENT, "Đêm nhạc", "Quy định", true, 48, true, Instant.parse("2026-10-22T03:00:00Z")));

            call(json(put("/v1/support/knowledge/rules/{id}", EVENT), RULES_BODY), staff())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
                    .andExpect(jsonPath("$.refundWindowHours").value(48));

            verify(knowledge).upsertRules(EVENT, "Đêm nhạc", "Quy định", true, 48, true);
        }

        @Test
        @DisplayName("thiếu refundAllowed/published: mặc định false — không vô tình cho hoàn vé hay công bố bản nháp")
        void mac_dinh_an_toan() throws Exception {
            when(knowledge.upsertRules(any(), anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean()))
                    .thenReturn(null);

            call(
                            json(
                                    put("/v1/support/knowledge/rules/{id}", EVENT),
                                    "{\"eventTitle\":\"t\",\"content\":\"c\"}"),
                            staff())
                    .andExpect(status().isOk());

            verify(knowledge).upsertRules(EVENT, "t", "c", false, 0, false);
        }

        @Test
        @DisplayName("refundWindowHours ngoài [0, 8760] hoặc thân không hợp lệ: 400")
        void khong_hop_le() throws Exception {
            for (String hours : List.of("-1", "8761", "99999999")) {
                call(
                                json(
                                        put("/v1/support/knowledge/rules/{id}", EVENT),
                                        "{\"eventTitle\":\"t\",\"content\":\"c\",\"refundWindowHours\":" + hours + "}"),
                                staff())
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.meta.fields.refundWindowHours").exists());
            }
            call(
                            json(
                                    put("/v1/support/knowledge/rules/{id}", EVENT),
                                    "{\"eventTitle\":\"\",\"content\":\"c\"}"),
                            staff())
                    .andExpect(status().isBadRequest());
            call(
                            json(
                                    put("/v1/support/knowledge/rules/{id}", EVENT),
                                    "{\"eventTitle\":\"t\",\"content\":\"" + "c".repeat(20001) + "\"}"),
                            staff())
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(knowledge);
        }

        @Test
        @DisplayName("biên 0 và 8760 giờ hợp lệ")
        void bien_hop_le() throws Exception {
            when(knowledge.upsertRules(any(), anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean()))
                    .thenReturn(null);

            for (String hours : List.of("0", "8760")) {
                call(
                                json(
                                        put("/v1/support/knowledge/rules/{id}", EVENT),
                                        "{\"eventTitle\":\"t\",\"content\":\"c\",\"refundWindowHours\":" + hours + "}"),
                                staff())
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("đọc quy định (kể cả bản nháp)")
        void doc() throws Exception {
            when(knowledge.rules(EVENT))
                    .thenReturn(new KnowledgeViews.RulesRow(EVENT, "t", "c", false, 0, false, Instant.EPOCH));

            call(get("/v1/support/knowledge/rules/{id}", EVENT), staff())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.published").value(false));
        }
    }
}
