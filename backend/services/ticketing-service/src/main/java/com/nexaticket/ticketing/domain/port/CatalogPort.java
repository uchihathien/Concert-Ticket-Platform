// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Đọc giờ diễn và tên sự kiện của một suất từ catalog-service.
 *
 * <p>Vé không mang hai thứ đó: mã QR cố ý không chứa gì, và bảng {@code tickets} chỉ chụp lại
 * những gì in trên vé.
 */
public interface CatalogPort {

    /**
     * Trả {@link Optional#empty()} cho <b>mọi</b> tình huống không lấy được — suất không tồn tại,
     * mạng hỏng, catalog đang sập.
     *
     * <p>Gộp lại là cố ý, cùng lý do với {@link IdentityPort}: ở cửa vào, một catalog im lặng
     * không được phép chặn cả hàng người. Không có giờ diễn thì bỏ qua bước kiểm giờ; chữ ký, đúng
     * suất và trong/ngoài vẫn được kiểm đủ bằng dữ liệu của chính ticketing.
     */
    Optional<SessionInfo> sessionInfo(UUID eventSessionId);

    /**
     * @param eventId sự kiện chứa suất này — thứ việc gán nhân viên soát vé được khai theo
     * @param endsAt {@code null} khi ban tổ chức không khai giờ kết thúc
     */
    record SessionInfo(UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}

    /**
     * Lịch diễn của nhiều sự kiện. Sự kiện không tồn tại thì không có trong map.
     *
     * @throws CatalogUnavailableException khi catalog không trả lời — KHÔNG trả map rỗng, vì "không
     *     có sự kiện nào" và "không biết" dẫn tới hai quyết định ngược nhau ở chỗ gọi
     */
    java.util.Map<UUID, EventSchedule> eventSchedules(java.util.Collection<UUID> eventIds);

    /** @param sessions mọi suất, kể cả đã qua */
    record EventSchedule(
            UUID eventId,
            UUID organizationId,
            String eventTitle,
            String venueName,
            java.util.List<com.nexaticket.ticketing.domain.model.AssignmentWindow.Session> sessions) {}

    class CatalogUnavailableException extends RuntimeException {

        public CatalogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
