// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.idempotency.IdempotencyFilter;
import com.nexaticket.platform.idempotency.IdempotencyProperties;
import com.nexaticket.platform.idempotency.IdempotencyStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hành vi của {@link IdempotencyFilter} trên một controller giả đếm số lần handler chạy.
 *
 * <p>Nằm trong package của {@code TenantContext} chỉ để dùng {@link TenantContextFixture}: {@code set}
 * của ngữ cảnh người dùng cố ý không mở ra ngoài package. Store là Mockito — ngữ nghĩa SQL thật được
 * kiểm ở {@code IdempotencyFilterIT}.
 */
class IdempotencyFilterTest {

    private static final String PATH = "/v1/orders";
    private static final String BODY = "{\"holdId\":\"h-1\"}";

    private final UUID user = UUID.randomUUID();
    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final AtomicInteger handlerRuns = new AtomicInteger();
    private MockMvc mvc;

    @RestController
    class Echo {
        @PostMapping(PATH)
        org.springframework.http.ResponseEntity<String> place(@RequestBody String body) {
            handlerRuns.incrementAndGet();
            if (body.contains("boom")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "het cho");
            }
            return org.springframework.http.ResponseEntity.status(201)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"orderId\":\"o-1\",\"echo\":" + body + "}");
        }

        @PostMapping("/v1/other")
        String other() {
            handlerRuns.incrementAndGet();
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        TenantContextFixture.customer(UserId.of(user));
        IdempotencyProperties props = new IdempotencyProperties(List.of(PATH), Duration.ofMinutes(2), 1024);
        mvc = MockMvcBuilders.standaloneSetup(new Echo())
                .addFilters(new IdempotencyFilter(props, Optional.of(store), new ObjectMapper()))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextFixture.clear();
    }

    @Nested
    @DisplayName("Khoá bắt buộc")
    class KhoaBatBuoc {

        @Test
        @DisplayName("Thiếu header: 400 IDEMPOTENCY_KEY_REQUIRED, handler và store không bị chạm")
        void thieu_header() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                    .andExpect(jsonPath("$.status").value(400));

            assertThat(handlerRuns).hasValue(0);
            verify(store, never()).tryBegin(any(), any(), any());
        }

        @Test
        @DisplayName("Khoá rỗng, có khoảng trắng, có xuống dòng hoặc dài quá 255: 400")
        void khoa_sai_khuon() throws Exception {
            for (String bad : List.of("", "   ", "co khoang trang", "xuong\ndong", "x".repeat(256))) {
                mvc.perform(post(PATH)
                                .header("Idempotency-Key", bad)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                        .andExpect(status().isBadRequest());
            }
            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("Route không khai, hoặc không phải POST: bộ lọc đứng ngoài")
        void ngoai_pham_vi() throws Exception {
            mvc.perform(post("/v1/other")).andExpect(status().isOk());
            mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());

            assertThat(handlerRuns).hasValue(1);
            verify(store, never()).tryBegin(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Lần đầu")
    class LanDau {

        @Test
        @DisplayName("Chạy handler một lần, lưu câu trả lời thành công theo (người dùng, khoá)")
        void luu_cau_tra_loi() throws Exception {
            when(store.tryBegin(eq(user), eq("key-0001"), any())).thenReturn(true);

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value("o-1"));

            assertThat(handlerRuns).hasValue(1);
            verify(store).complete(eq(user), eq("key-0001"), eq(201), any());
            verify(store, never()).release(any(), any());
        }

        @Test
        @DisplayName("Handler thấy nguyên thân request dù bộ lọc đã đọc nó để băm")
        void than_con_nguyen() throws Exception {
            when(store.tryBegin(any(), any(), any())).thenReturn(true);

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0002")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(jsonPath("$.echo.holdId").value("h-1"));
        }

        @Test
        @DisplayName("Handler lỗi (409): không lưu, bỏ chỗ đã giữ để client thử lại được ngay")
        void loi_thi_nha_cho() throws Exception {
            when(store.tryBegin(any(), any(), any())).thenReturn(true);

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0003")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"boom\":true}"))
                    .andExpect(status().isConflict());

            verify(store, never()).complete(any(), any(), anyInt(), any());
            verify(store).release(user, "key-0003");
        }

        @Test
        @DisplayName("Thân vượt trần: 400, không giữ chỗ trong store")
        void than_qua_lon() throws Exception {
            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0004")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("x".repeat(2000)))
                    .andExpect(status().isBadRequest());

            verify(store, never()).tryBegin(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Gửi lại")
    class GuiLai {

        @Test
        @DisplayName("Cùng khoá, cùng nội dung, đã hoàn tất: phát lại đúng câu trả lời, handler KHÔNG chạy")
        void phat_lai() throws Exception {
            String hash = hashOfFirstCall();
            when(store.tryBegin(any(), eq("key-0005"), any())).thenReturn(false);
            when(store.takeOverIfStale(any(), any(), any(), any())).thenReturn(false);
            when(store.find(user, "key-0005"))
                    .thenReturn(Optional.of(
                            new IdempotencyStore.Existing(hash, 201, "{\"orderId\":\"o-1\",\"replayed\":true}")));
            handlerRuns.set(0);

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0005")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.replayed").value(true));

            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("Cùng khoá, nội dung KHÁC: 409 IDEMPOTENCY_KEY_REUSED")
        void khoa_dung_lai() throws Exception {
            when(store.tryBegin(any(), any(), any())).thenReturn(false);
            when(store.find(user, "key-0006"))
                    .thenReturn(Optional.of(new IdempotencyStore.Existing("hash-khac", 201, "{}")));

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0006")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("Request đầu còn đang chạy: 409 REQUEST_IN_PROGRESS, không chạy song song")
        void dang_chay() throws Exception {
            String hash = hashOfFirstCall();
            when(store.tryBegin(any(), any(), any())).thenReturn(false);
            when(store.find(user, "key-0007")).thenReturn(Optional.of(new IdempotencyStore.Existing(hash, null, null)));
            handlerRuns.set(0);

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0007")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));

            assertThat(handlerRuns).hasValue(0);
        }

        @Test
        @DisplayName("Bản ghi dở của tiến trình đã chết (quá hạn): được nhận lại và handler chạy")
        void nhan_lai_ban_ghi_cu() throws Exception {
            when(store.tryBegin(any(), any(), any())).thenReturn(false);
            when(store.takeOverIfStale(eq(user), eq("key-0008"), any(), eq(Duration.ofMinutes(2))))
                    .thenReturn(true);

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", "key-0008")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isCreated());

            assertThat(handlerRuns).hasValue(1);
            verify(store).complete(eq(user), eq("key-0008"), eq(201), any());
        }
    }

    @Test
    @DisplayName("Khoá có phạm vi theo người dùng: cùng chuỗi khoá của hai người là hai bản ghi")
    void pham_vi_nguoi_dung() throws Exception {
        UUID other = UUID.randomUUID();
        when(store.tryBegin(any(), any(), any())).thenReturn(true);

        mvc.perform(post(PATH)
                .header("Idempotency-Key", "dung-chung")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
        TenantContextFixture.customer(UserId.of(other));
        mvc.perform(post(PATH)
                .header("Idempotency-Key", "dung-chung")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));

        verify(store).tryBegin(eq(user), eq("dung-chung"), any());
        verify(store).tryBegin(eq(other), eq("dung-chung"), any());
    }

    /** Gọi một lần để lấy dấu vân tay thật mà filter tính cho (BODY, PATH) rồi trả về. */
    private String hashOfFirstCall() throws Exception {
        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        when(store.tryBegin(any(), eq("probe"), hash.capture())).thenReturn(true);
        mvc.perform(post(PATH)
                .header("Idempotency-Key", "probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
        return hash.getValue();
    }
}
