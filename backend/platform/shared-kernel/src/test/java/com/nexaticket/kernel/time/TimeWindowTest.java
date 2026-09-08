// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeWindowTest {

    private static TimeWindow window(String from, String to) {
        return new TimeWindow(Instant.parse(from), Instant.parse(to));
    }

    @Test
    void hai_suat_khong_giao_gio_dien_thi_khong_trung() {
        TimeWindow matinee = window("2026-11-01T07:00:00Z", "2026-11-01T09:00:00Z");
        TimeWindow evening = window("2026-11-01T12:00:00Z", "2026-11-01T15:00:00Z");
        assertThat(matinee.overlaps(evening)).isFalse();
    }

    @Test
    void nhung_them_dem_dung_thao_thi_trung() {
        // ADR-1013: khoảng chiếm dụng gồm cả thời gian dựng và tháo
        Duration setup = Duration.ofMinutes(240);
        Duration teardown = Duration.ofMinutes(180);
        TimeWindow matinee =
                window("2026-11-01T07:00:00Z", "2026-11-01T09:00:00Z").expandedBy(setup, teardown);
        TimeWindow evening =
                window("2026-11-01T12:00:00Z", "2026-11-01T15:00:00Z").expandedBy(setup, teardown);
        assertThat(matinee.overlaps(evening)).isTrue();
    }

    @Test
    void khoang_nua_mo_khong_tinh_diem_cuoi() {
        TimeWindow w = window("2026-11-01T07:00:00Z", "2026-11-01T09:00:00Z");
        assertThat(w.contains(Instant.parse("2026-11-01T07:00:00Z"))).isTrue();
        assertThat(w.contains(Instant.parse("2026-11-01T09:00:00Z"))).isFalse();
    }
}
