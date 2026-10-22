// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
     * Mọi suất của những sự kiện này. {@link Optional#empty()} khi catalog không trả lời — khác
     * {@link #sessionInfo}, người gọi ở đây (ca trực, hạn tài khoản nhân viên) cần biết "không hỏi
     * được" để KHÔNG kết luận gì, thay vì coi như sự kiện không có suất nào.
     */
    Optional<List<SessionSlot>> sessionsOfEvents(Collection<UUID> eventIds);

    /**
     * @param eventId sự kiện chứa suất này — thứ việc gán nhân viên soát vé được khai theo
     * @param endsAt {@code null} khi ban tổ chức không khai giờ kết thúc
     */
    record SessionInfo(UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}

    /** Một suất kèm id của chính nó — {@link SessionInfo} không mang id vì người hỏi đã biết. */
    record SessionSlot(UUID eventSessionId, UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}
}
