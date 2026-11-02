// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Khung hiệu lực của một phân công soát vé, tính từ lịch diễn.
 *
 * <p>Luật: phân công hết hiệu lực khi suất <b>cuối</b> trong phạm vi được gán kết thúc quá
 * {@link #GRACE} (6 giờ). Phạm vi là cả sự kiện khi không chỉ định suất, hoặc đúng một suất.
 *
 * <p>Sáu giờ sau giờ kết thúc chứ không phải đúng giờ kết thúc: đêm diễn kéo dài, khách vào muộn,
 * soát vé ra/vào lại sau giờ — cửa vẫn cần người. Và không có job nào phải chạy để "khoá" ai: hạn
 * được so ở mỗi lượt quét.
 *
 * <p>Suất không khai giờ kết thúc thì coi như kéo dài {@link #FALLBACK_DURATION} kể từ giờ bắt đầu
 * — cùng giá trị app soát vé dùng để đoán giờ đóng cổng.
 *
 * @param startsAt giờ bắt đầu sớm nhất trong phạm vi; {@code null} khi chưa có suất nào
 * @param expiresAt hết hiệu lực lúc này; {@code null} khi chưa có suất nào (chưa thể hết hạn)
 */
public record AssignmentWindow(Instant startsAt, Instant expiresAt) {

    public static final Duration GRACE = Duration.ofHours(6);
    public static final Duration FALLBACK_DURATION = Duration.ofHours(6);

    /** Trạng thái hiển thị cho ban tổ chức và nhân viên. */
    public enum Status {
        UPCOMING,
        ONGOING,
        EXPIRED,
        /** Không đọc được lịch diễn — không kết luận được gì. */
        UNKNOWN
    }

    /** Một suất trong lịch diễn. {@code endsAt} có thể null. */
    public record Session(UUID id, Instant startsAt, Instant endsAt) {

        Instant effectiveEnd() {
            return endsAt != null ? endsAt : startsAt.plus(FALLBACK_DURATION);
        }
    }

    /**
     * @param sessions mọi suất của sự kiện
     * @param sessionId {@code null} là cả sự kiện
     */
    public static AssignmentWindow of(List<Session> sessions, UUID sessionId) {
        List<Session> inScope = sessions.stream()
                .filter(Objects::nonNull)
                .filter(session -> session.startsAt() != null)
                .filter(session -> sessionId == null || sessionId.equals(session.id()))
                .toList();
        if (inScope.isEmpty()) {
            return new AssignmentWindow(null, null);
        }
        Instant first = inScope.stream()
                .map(Session::startsAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Instant last = inScope.stream()
                .map(Session::effectiveEnd)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new AssignmentWindow(first, last.plus(GRACE));
    }

    /** Còn hiệu lực lúc {@code now}. Chưa có suất nào thì còn — gán trước, thêm suất sau là bình thường. */
    public boolean activeAt(Instant now) {
        return expiresAt == null || !now.isAfter(expiresAt);
    }

    public Status statusAt(Instant now) {
        if (!activeAt(now)) {
            return Status.EXPIRED;
        }
        return startsAt != null && !now.isBefore(startsAt) ? Status.ONGOING : Status.UPCOMING;
    }
}
