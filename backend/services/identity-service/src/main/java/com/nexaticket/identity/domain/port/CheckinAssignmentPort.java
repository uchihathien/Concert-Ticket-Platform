// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Việc gán nhân viên soát vé vào sự kiện — <b>dữ liệu của ticketing-service</b>.
 *
 * <p>Identity không giữ bản sao nào. Câu hỏi "người này được soát sự kiện nào" được trả lời ở lượt
 * quét, tức là ở ticketing; hai nơi cùng lưu là hai nơi sẽ lệch nhau, và nơi lệch sẽ là nơi cho
 * một người đã bị gỡ tiếp tục quét vé.
 */
public interface CheckinAssignmentPort {

    /** Sự kiện của từng nhân viên trong một tổ chức. Người chưa được gán gì không có trong map. */
    Map<UserId, List<UUID>> byOrganization(TenantId organizationId);

    List<UUID> eventsOf(TenantId organizationId, UserId userId);

    /** Đặt lại danh sách sự kiện của một nhân viên thành đúng {@code eventIds}. Rỗng là gỡ hết. */
    void replace(TenantId organizationId, UserId userId, Collection<UUID> eventIds, UserId assignedBy);

    /** ticketing-service không trả lời. Tách khỏi lỗi nghiệp vụ để người gọi báo "thử lại sau". */
    class AssignmentsUnavailableException extends RuntimeException {

        public AssignmentsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
