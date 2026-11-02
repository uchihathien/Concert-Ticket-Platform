// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
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
 *
 * <p>Identity vẫn là cửa ghi DUY NHẤT: chỉ ở đây mới biết người được gán có đang là
 * {@code CHECKIN_STAFF} của tổ chức hay không, nên mọi lệnh gán đi qua identity rồi mới sang
 * ticketing.
 */
public interface CheckinAssignmentPort {

    /**
     * Phân công của từng nhân viên trong một tổ chức, kể cả phân công đã hết hạn (để xem lịch sử).
     * Người chưa từng được gán không có trong map.
     */
    Map<UserId, List<Assignment>> byOrganization(TenantId organizationId);

    /** Phân công của một nhân viên, kể cả đã hết hạn. */
    List<Assignment> assignmentsOf(TenantId organizationId, UserId userId);

    /**
     * Đặt lại các phân công CÒN HIỆU LỰC của một nhân viên thành đúng {@code targets}. Rỗng là gỡ
     * hết. Phân công đã hết hạn không bị đụng tới — chúng là lịch sử.
     */
    void assign(TenantId organizationId, UserId userId, Collection<Target> targets, UserId assignedBy);

    /**
     * Gỡ mọi phân công còn hiệu lực — dùng khi người này bị gỡ khỏi tổ chức hoặc thôi làm nhân viên
     * soát vé. Phân công đã hết hạn được giữ lại.
     */
    void revokeActive(TenantId organizationId, UserId userId);

    /** Sự kiện của những phân công còn hiệu lực. */
    default List<UUID> eventsOf(TenantId organizationId, UserId userId) {
        return assignmentsOf(organizationId, userId).stream()
                .filter(Assignment::active)
                .map(Assignment::eventId)
                .distinct()
                .toList();
    }

    /** Bản cũ, gán theo cả sự kiện. Giữ cho luồng mời nhân viên đã ngừng vẫn biên dịch được. */
    default void replace(TenantId organizationId, UserId userId, Collection<UUID> eventIds, UserId assignedBy) {
        assign(
                organizationId,
                userId,
                eventIds.stream().map(eventId -> new Target(eventId, null)).toList(),
                assignedBy);
    }

    /**
     * Một mục cần gán.
     *
     * @param eventSessionId {@code null} là gán cả sự kiện — mọi suất, kể cả suất thêm sau
     */
    record Target(UUID eventId, UUID eventSessionId) {}

    /**
     * Một phân công, kèm khung giờ ticketing tính từ lịch diễn.
     *
     * @param eventSessionId {@code null} khi gán cả sự kiện
     * @param startsAt giờ bắt đầu của suất đầu tiên trong phạm vi được gán; {@code null} khi chưa
     *     đọc được lịch
     * @param expiresAt hết hiệu lực lúc này (suất cuối kết thúc + 6 giờ)
     * @param status {@code UPCOMING}, {@code ONGOING}, {@code EXPIRED} hoặc {@code UNKNOWN} khi
     *     không đọc được lịch diễn
     */
    record Assignment(
            UUID eventId, UUID eventSessionId, String eventTitle, Instant startsAt, Instant expiresAt, String status) {

        public boolean active() {
            return !"EXPIRED".equals(status);
        }
    }

    /** ticketing-service không trả lời. Tách khỏi lỗi nghiệp vụ để người gọi báo "thử lại sau". */
    class AssignmentsUnavailableException extends RuntimeException {

        public AssignmentsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * ticketing từ chối lệnh gán vì một mục không hợp lệ — sự kiện không thuộc tổ chức, suất không
     * thuộc sự kiện. Lỗi của người gọi, không phải của hạ tầng: không có ý nghĩa gì khi thử lại.
     */
    class AssignmentRejectedException extends RuntimeException {

        private final String code;

        public AssignmentRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
