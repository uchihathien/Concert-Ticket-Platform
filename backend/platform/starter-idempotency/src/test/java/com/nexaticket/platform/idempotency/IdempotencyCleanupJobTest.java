// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Vòng lặp chia lô của {@link IdempotencyCleanupJob}; câu SQL thật được kiểm ở {@code IdempotencyCleanupJobIT}. */
class IdempotencyCleanupJobTest {

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyProperties props =
            new IdempotencyProperties(null, null, null, Duration.ofHours(48), null, 100);
    private final IdempotencyCleanupJob job = new IdempotencyCleanupJob(store, props);

    @Test
    @DisplayName("Mặc định giữ 48 giờ, dọn mỗi giờ, 1.000 dòng một lô — không ít hơn 24 giờ của ADR-0007")
    void mac_dinh() {
        IdempotencyProperties defaults = new IdempotencyProperties(null, null, null, null, null, null);

        assertThat(defaults.retention()).isEqualTo(Duration.ofHours(48)).isGreaterThanOrEqualTo(Duration.ofHours(24));
        assertThat(defaults.cleanupInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.cleanupBatch()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Không có gì cũ: một câu lệnh duy nhất, trả 0")
    void khong_co_gi() {
        when(store.purgeOlderThan(eq(Duration.ofHours(48)), eq(100))).thenReturn(0);

        assertThat(job.runOnce()).isZero();

        verify(store, times(1)).purgeOlderThan(Duration.ofHours(48), 100);
    }

    @Test
    @DisplayName("Lô đầy thì xoá tiếp tới khi lô không đầy; tổng là tổng số dòng đã xoá")
    void chia_lo() {
        when(store.purgeOlderThan(eq(Duration.ofHours(48)), eq(100))).thenReturn(100, 100, 37);

        assertThat(job.runOnce()).isEqualTo(237);

        verify(store, times(3)).purgeOlderThan(Duration.ofHours(48), 100);
    }

    @Test
    @DisplayName("Có thứ chèn nhanh hơn ta xoá: dừng sau số lô tối đa, vòng sau dọn tiếp")
    void chan_vong_lap_vo_han() {
        when(store.purgeOlderThan(eq(Duration.ofHours(48)), anyInt())).thenReturn(100);

        assertThat(job.runOnce()).isEqualTo(100 * IdempotencyCleanupJob.MAX_BATCHES_PER_RUN);

        verify(store, times(IdempotencyCleanupJob.MAX_BATCHES_PER_RUN)).purgeOlderThan(Duration.ofHours(48), 100);
    }
}
