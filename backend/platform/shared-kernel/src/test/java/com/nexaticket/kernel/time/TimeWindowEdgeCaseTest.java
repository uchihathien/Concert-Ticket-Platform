// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Biên của {@link TimeWindow}, bổ sung cho {@code TimeWindowTest}. */
class TimeWindowEdgeCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-01T07:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-01T11:00:00Z");

    @Test
    @DisplayName("end phải SAU start: bằng nhau hay ngược đều bị từ chối")
    void rang_buoc_start_end() {
        assertThatThrownBy(() -> new TimeWindow(T0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeWindow(T1, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeWindow(null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeWindow(T0, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("hai khoảng liền kề (end = start kia) không giao nhau — hai suất nối tiếp không bị báo trùng")
    void lien_ke_khong_giao() {
        TimeWindow a = new TimeWindow(T0, T1);
        TimeWindow b = new TimeWindow(T1, T2);

        assertThat(a.overlaps(b)).isFalse();
        assertThat(b.overlaps(a)).isFalse();
    }

    @Test
    @DisplayName("giao nhau đối xứng, lồng nhau và trùng hệt đều là giao")
    void giao_nhau() {
        TimeWindow outer = new TimeWindow(T0, T2);
        TimeWindow inner = new TimeWindow(T0.plusSeconds(60), T1);

        assertThat(outer.overlaps(inner)).isTrue();
        assertThat(inner.overlaps(outer)).isTrue();
        assertThat(outer.overlaps(outer)).isTrue();
        assertThat(new TimeWindow(T0, T1).overlaps(new TimeWindow(T1.minusSeconds(1), T2)))
                .isTrue();
    }

    @Test
    @DisplayName("contains: gồm start, loại end, loại điểm ngoài")
    void contains() {
        TimeWindow w = new TimeWindow(T0, T1);

        assertThat(w.contains(T0)).isTrue();
        assertThat(w.contains(T1.minusMillis(1))).isTrue();
        assertThat(w.contains(T1)).isFalse();
        assertThat(w.contains(T0.minusMillis(1))).isFalse();
    }

    @Test
    @DisplayName("expandedBy nới đúng hai đầu; đệm 0 giữ nguyên; bản gốc bất biến")
    void expanded_by() {
        TimeWindow w = new TimeWindow(T0, T1);

        TimeWindow wider = w.expandedBy(Duration.ofMinutes(30), Duration.ofMinutes(15));

        assertThat(wider.start()).isEqualTo(T0.minus(Duration.ofMinutes(30)));
        assertThat(wider.end()).isEqualTo(T1.plus(Duration.ofMinutes(15)));
        assertThat(w.expandedBy(Duration.ZERO, Duration.ZERO)).isEqualTo(w);
        assertThat(w.start()).isEqualTo(T0);
    }

    @Test
    @DisplayName("đệm âm thu hẹp khoảng; thu hẹp quá mức thì bị từ chối thay vì tạo khoảng ngược")
    void dem_am() {
        TimeWindow w = new TimeWindow(T0, T1);

        assertThat(w.expandedBy(Duration.ofMinutes(-10), Duration.ofMinutes(-10))
                        .duration())
                .isEqualTo(Duration.ofMinutes(100));
        assertThatThrownBy(() -> w.expandedBy(Duration.ofHours(-2), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("duration bằng end - start")
    void duration() {
        assertThat(new TimeWindow(T0, T1).duration()).isEqualTo(Duration.ofHours(2));
    }
}
