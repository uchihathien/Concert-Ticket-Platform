// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kho đề xuất đặt vé.
 *
 * <p>Mọi phép chuyển trạng thái là <b>một</b> câu {@code UPDATE … WHERE status = 'PROPOSED'} nguyên
 * tử: hai lệnh đua nhau (khách bấm Đồng ý đúng lúc job hết hạn chạy, hoặc bấm hai lần) thì đúng một
 * lệnh thấy dòng còn PROPOSED và thắng; lệnh kia nhận {@code false}/0 và đọc lại để biết chuyện gì
 * đã xảy ra. Không có khoá phân tán, không có phiên bản.
 */
public interface BookingProposalRepository {

    /**
     * Chèn đề xuất mới.
     *
     * @throws org.springframework.dao.DuplicateKeyException đã có đề xuất PROPOSED cùng
     *     {@code (userId, eventSessionId, zoneCode)} — index bộ phận {@code uq_booking_proposal_open}.
     *     Người gọi quyết: trả lại cái cũ, hay {@link #cancelOpen} rồi chèn lại
     */
    void insert(BookingProposal proposal);

    Optional<BookingProposal> findById(UUID id);

    /**
     * PROPOSED → CANCELLED cho mọi đề xuất đang mở của khách ở một khu — để thay bằng đề xuất mới
     * khi khách đổi số vé.
     *
     * @return số dòng đã huỷ (0 hoặc 1 nhờ unique bộ phận)
     */
    int cancelOpen(UUID userId, UUID eventSessionId, String zoneCode, Instant now);

    /**
     * PROPOSED → CANCELLED một đề xuất, chỉ khi đúng chủ.
     *
     * @return {@code false} nếu không còn PROPOSED hoặc khác chủ — người gọi đọc lại để phân biệt
     */
    boolean cancel(UUID id, UUID userId, Instant now);

    /**
     * PROPOSED → CONFIRMED, ghi đơn vừa tạo và {@code confirmedAt = now}.
     *
     * @return {@code false} nếu đề xuất đã rời PROPOSED (bấm hai lần, hết hạn vừa chạy) — đơn
     *     {@code order} khi đó đã được tạo ở ordering, người gọi phải xử lý, không được lặng im
     */
    boolean markConfirmed(UUID id, BookingProposal.Order order, Instant now);

    /**
     * PROPOSED và {@code expiresAt <= now} → EXPIRED. Instance thứ hai chạy cùng lúc chỉ thấy 0 dòng.
     *
     * @return số dòng đã hết hạn
     */
    int expireOverdue(Instant now);

    /** Đếm theo trạng thái — đủ bốn khoá, 0 cho trạng thái không có dòng. */
    Map<BookingProposalStatus, Long> countByStatus();
}
