// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Khoảng thời gian cửa nhận khách của một suất diễn.
 *
 * <p>Catalog không có cột "giờ mở cổng", nên cửa mở một khoảng cố định trước giờ diễn. Suất không
 * khai giờ kết thúc thì đóng sau một khoảng mặc định — không có giới hạn trên nghĩa là vé của đêm
 * qua vẫn vào được đêm nay.
 */
public record GateWindow(Instant opensAt, Instant closesAt) {

    public static GateWindow of(Instant startsAt, Instant endsAt, Duration openBefore, Duration fallbackDuration) {
        return new GateWindow(startsAt.minus(openBefore), endsAt != null ? endsAt : startsAt.plus(fallbackDuration));
    }

    /**
     * @return {@code null} nếu cửa đang mở; ngược lại là lý do từ chối
     */
    public CheckinResult check(Instant now) {
        if (now.isBefore(opensAt)) {
            return CheckinResult.NOT_OPEN_YET;
        }
        return now.isAfter(closesAt) ? CheckinResult.SESSION_ENDED : null;
    }
}
