// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Nhân viên soát vé được soát sự kiện nào. Ticketing là nơi duy nhất lưu việc gán này. */
public interface CheckinAssignmentRepository {

    boolean isAssigned(UUID organizationId, UUID userId, UUID eventId);

    /**
     * @param userId {@code null} để lấy của mọi nhân viên trong tổ chức
     */
    List<Assignment> find(UUID organizationId, UUID userId);

    /**
     * Đặt lại danh sách sự kiện của một nhân viên trong một tổ chức thành đúng {@code eventIds}.
     *
     * <p>Thay cả tập chứ không thêm/bớt từng dòng: màn hình Nhân sự gửi lên trạng thái mong muốn,
     * và hai lần bấm Lưu liên tiếp phải cho cùng một kết quả.
     */
    void replace(UUID organizationId, UUID userId, Collection<UUID> eventIds, UUID assignedBy, Instant at);

    record Assignment(UUID userId, UUID eventId, UUID assignedBy, Instant assignedAt) {}
}
