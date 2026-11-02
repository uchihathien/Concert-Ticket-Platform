// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Nhân viên soát vé được soát sự kiện / suất nào. Ticketing là nơi duy nhất lưu việc gán này.
 *
 * <p>Repository không biết gì về hạn: hạn tính từ lịch diễn của catalog ở tầng application
 * ({@code AssignmentWindow}), nên dời giờ diễn thì hạn dời theo mà không phải sửa dòng nào.
 */
public interface CheckinAssignmentRepository {

    /** Mọi phân công của một người cho một sự kiện — cả sự kiện lẫn từng suất. */
    List<Assignment> forUserEvent(UUID organizationId, UUID userId, UUID eventId);

    /**
     * @param userId {@code null} để lấy của mọi nhân viên trong tổ chức
     */
    List<Assignment> find(UUID organizationId, UUID userId);

    /** Thêm các phạm vi còn thiếu. Phạm vi đã có (kể cả đã hết hạn) giữ nguyên người gán và giờ gán. */
    void insert(UUID organizationId, UUID userId, Collection<Scope> scopes, UUID assignedBy, Instant at);

    /** Xoá đúng những dòng có id trong danh sách, và chỉ của người này trong tổ chức này. */
    void delete(UUID organizationId, UUID userId, Collection<UUID> assignmentIds);

    /**
     * @param eventSessionId {@code null} là cả sự kiện
     */
    record Scope(UUID eventId, UUID eventSessionId) {}

    /**
     * @param eventSessionId {@code null} là gán cả sự kiện
     * @param assignedBy {@code null} khi dòng do script dữ liệu dev tạo
     */
    record Assignment(UUID id, UUID userId, UUID eventId, UUID eventSessionId, UUID assignedBy, Instant assignedAt) {

        public Scope scope() {
            return new Scope(eventId, eventSessionId);
        }
    }
}
