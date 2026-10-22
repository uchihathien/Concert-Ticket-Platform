// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportDeskAccess;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.interfaces.rest.SupportDeskController;
import com.nexaticket.aichatbox.interfaces.rest.SupportKnowledgeController;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Trang quá lớn là lỗi của người gọi (400), không phải 500 — trước đây {@code page * limit} nhân
 * {@code int} tràn thành OFFSET âm (R-07).
 */
class SupportDeskPagingTest {

    private final HandoffUseCase handoffs = mock(HandoffUseCase.class);
    private final KnowledgeBaseUseCase knowledge = mock(KnowledgeBaseUseCase.class);
    private final SupportDeskAccess access = mock(SupportDeskAccess.class);

    private MockMvc mvc() {
        when(access.requireSupportAgent()).thenReturn(UUID.randomUUID());
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(
                        new SupportDeskController(handoffs, access), new SupportKnowledgeController(knowledge, access))
                .setValidator(validator)
                .build();
    }

    @Test
    void trang_qua_lon_tra_400_khong_500() throws Exception {
        MockMvc mvc = mvc();

        mvc.perform(get("/v1/support/handoffs").param("page", "30000000")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/support/knowledge/chunks").param("page", "30000000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trang_hop_le_tinh_offset_dung_va_trang_am_thanh_0() throws Exception {
        when(handoffs.queue(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        MockMvc mvc = mvc();

        mvc.perform(get("/v1/support/handoffs").param("page", "3").param("size", "20"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/support/handoffs").param("page", "-5").param("size", "20"))
                .andExpect(status().isOk());

        verify(handoffs).queue(null, null, 20, 60);
        verify(handoffs).queue(null, null, 20, 0);
    }

    @Test
    void offset_nhan_bang_long_khong_tran() throws Exception {
        Method offsetOf = SupportDeskController.class.getDeclaredMethod("offsetOf", int.class, int.class);
        offsetOf.setAccessible(true);

        assertThat(offsetOf.invoke(null, 10_000, 100)).isEqualTo(1_000_000);
        assertThat(offsetOf.invoke(null, Integer.MAX_VALUE, 100)).isEqualTo(Integer.MAX_VALUE);
        assertThat(offsetOf.invoke(null, -1, 100)).isEqualTo(0);
    }

    @Test
    void danh_sach_tri_thuc_van_chay_voi_trang_hop_le() throws Exception {
        when(knowledge.listChunks(any(), anyInt(), anyInt())).thenReturn(List.of());

        mvc().perform(get("/v1/support/knowledge/chunks").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());

        verify(knowledge).listChunks(eq(null), eq(10), eq(20));
    }
}
