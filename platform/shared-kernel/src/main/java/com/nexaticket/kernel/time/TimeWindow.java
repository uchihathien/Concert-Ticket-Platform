// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Khoảng thời gian nửa mở [start, end). Dùng cho sales window và phát hiện trùng lịch địa điểm. */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start không được null");
        Objects.requireNonNull(end, "end không được null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end phải sau start: " + start + " .. " + end);
        }
    }

    public boolean contains(Instant moment) {
        return !moment.isBefore(start) && moment.isBefore(end);
    }

    /** Hai khoảng giao nhau. Dùng để phát hiện trùng lịch (ADR-1013). */
    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** Nới hai đầu — dùng cho đệm dựng/tháo của địa điểm. */
    public TimeWindow expandedBy(Duration before, Duration after) {
        return new TimeWindow(start.minus(before), end.plus(after));
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
