// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Danh bạ suất diễn của một tổ chức, cho các trang thống kê.
 *
 * <p>Số liệu ở ordering, inventory và ticketing chỉ mang mã suất; tên sự kiện và giờ diễn nằm ở
 * đây. Khác {@code CheckinSessionQuery}: danh bạ này gồm cả suất ĐÃ diễn xong và sự kiện ở mọi trạng
 * thái — báo cáo về một suất tháng trước vẫn phải gọi được tên nó.
 */
public final class SessionDirectory {

    private SessionDirectory() {}

    public record Entry(
            UUID eventSessionId,
            UUID eventId,
            String eventTitle,
            String eventStatus,
            Instant startsAt,
            Instant endsAt) {}

    public record Listing(List<Entry> sessions) {}

    /** Cổng đọc. */
    public interface Reader {
        List<Entry> sessionsOf(UUID organizationId);
    }
}
