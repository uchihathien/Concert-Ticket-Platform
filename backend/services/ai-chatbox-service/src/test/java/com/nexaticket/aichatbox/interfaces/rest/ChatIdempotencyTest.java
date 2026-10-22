// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.platform.idempotency.IdempotencyStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Chữ ký request tính trên thứ CLIENT gửi — không trên UUID server vừa sinh (R-15). */
class ChatIdempotencyTest {

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final ChatIdempotency idempotency = new ChatIdempotency(store, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    private String hashOf(UUID clientSessionId, String message) {
        when(store.tryBegin(any(), anyString(), anyString())).thenReturn(true);
        idempotency.beginOrReplay(userId, "k", clientSessionId, message);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).tryBegin(eq(userId), eq("k"), hash.capture());
        return hash.getValue();
    }

    @Test
    void hai_lan_gui_cau_dau_tien_cung_chu_ky() {
        String first = hashOf(null, "Đêm nhạc Trịnh còn vé không?");
        String second = hashOf(null, "Đêm nhạc Trịnh còn vé không?");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void phien_moi_khac_phien_cu_cung_noi_dung() {
        String fresh = hashOf(null, "xin chào");
        String existing = hashOf(UUID.randomUUID(), "xin chào");

        assertThat(existing).isNotEqualTo(fresh);
    }

    @Test
    void cung_phien_khac_noi_dung_khac_chu_ky() {
        UUID session = UUID.randomUUID();

        assertThat(hashOf(session, "a")).isNotEqualTo(hashOf(session, "b"));
    }
}
