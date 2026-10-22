// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link IdempotencyStore} và {@link ProcessedEvents} với JdbcTemplate giả.
 *
 * <p>Ngữ nghĩa thật (UNIQUE, ON CONFLICT) được kiểm ở {@code IdempotencyStoreIT}; ở đây ghim cách
 * câu lệnh được dựng và cách kết quả được dịch.
 */
class IdempotencyStoreTest {

    private static final UUID USER = UUID.fromString("33333333-3333-4333-a333-333333333333");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdempotencyStore store = new IdempotencyStore(jdbc);
    private final ProcessedEvents processed = new ProcessedEvents(jdbc);

    @Test
    @DisplayName("tryBegin: INSERT ... ON CONFLICT DO NOTHING; 1 dòng = giành được, 0 = đã có")
    void try_begin_theo_so_dong() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);

        assertThat(store.tryBegin(USER, "k1", "h")).isTrue();
        assertThat(store.tryBegin(USER, "k1", "h")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("ON CONFLICT (user_id, idem_key) DO NOTHING");
    }

    @Test
    @DisplayName("find: không có bản ghi thì rỗng, không ném")
    void find_khong_co() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(store.find(USER, "k")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("find: dùng IS NOT DISTINCT FROM để khoá của người chưa đăng nhập (user null) vẫn khớp")
    void find_user_null() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new IdempotencyStore.Existing("h", 200, "{}"));

        Optional<IdempotencyStore.Existing> found = store.find(null, "k");

        assertThat(found).isPresent();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("user_id IS NOT DISTINCT FROM ?");
    }

    @Test
    @DisplayName("Existing.isComplete: có status thì xong, chưa có thì đang chạy")
    void existing_is_complete() {
        assertThat(new IdempotencyStore.Existing("h", 200, "{}").isComplete()).isTrue();
        assertThat(new IdempotencyStore.Existing("h", null, null).isComplete()).isFalse();
    }

    @Test
    @DisplayName("complete: ghi status, body JSON và thời điểm xong")
    void complete_ghi_ket_qua() {
        store.complete(USER, "k", 201, "{\"id\":1}");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(201), eq("{\"id\":1}"), eq(USER), eq("k"));
        assertThat(sql.getValue()).contains("completed_at = now()").contains("::jsonb");
    }

    @Test
    @DisplayName("release: chỉ xoá bản ghi CHƯA hoàn tất — không xoá câu trả lời đã lưu")
    void release_chi_xoa_ban_chua_xong() {
        store.release(USER, "k");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(USER), eq("k"));
        assertThat(sql.getValue()).contains("DELETE FROM idempotency_records").contains("response_status IS NULL");
    }

    @Test
    @DisplayName("ProcessedEvents.markIfNew: true lần đầu, false khi trùng (0 dòng được chèn)")
    void mark_if_new() {
        when(jdbc.update(anyString(), eq("q"), eq("e1"))).thenReturn(1, 0);

        assertThat(processed.markIfNew("q", "e1")).isTrue();
        assertThat(processed.markIfNew("q", "e1")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), eq("q"), eq("e1"));
        assertThat(sql.getValue()).contains("ON CONFLICT (consumer_queue, event_id) DO NOTHING");
    }
}
