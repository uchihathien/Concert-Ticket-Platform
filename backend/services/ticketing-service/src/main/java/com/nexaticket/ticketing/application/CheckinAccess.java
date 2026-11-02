// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository.Assignment;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Ai được soát vé của suất nào — kiểm ở <b>mọi</b> lượt quét, không dựa vào việc app ẩn hiện gì.
 *
 * <p>Hai đường vào, và chỉ hai:
 *
 * <ul>
 *   <li>Người quản lý được sự kiện của tổ chức ({@link Permission#CATALOG_MANAGE} — chủ tổ chức,
 *       quản trị, quản lý sự kiện): soát mọi sự kiện <b>của tổ chức mình</b>, không cần được gán.
 *   <li>Người chỉ có {@link Permission#CHECKIN_SCAN} (thành viên vai Staff): chỉ những sự kiện hoặc
 *       suất ban tổ chức đã gán cho họ, và chỉ khi phân công <b>còn hiệu lực</b> — suất cuối trong
 *       phạm vi được gán chưa kết thúc quá 6 giờ.
 * </ul>
 *
 * <p>Hết hạn không cần job nào khoá tài khoản: hạn được so ngay ở đây. Người đó vẫn là thành viên
 * Staff của tổ chức để lần sau ban tổ chức gán tiếp.
 *
 * <p>"Của tổ chức mình" không kiểm ở đây mà ở {@code CheckInHandler}: vé phải thuộc tổ chức trên
 * đường dẫn, nên người của tổ chức khác không soát được dù lọt qua bước này.
 */
@Component
public class CheckinAccess {

    private final CheckinAssignmentRepository assignments;
    private final CatalogPort catalog;
    private final Clock clock;

    public CheckinAccess(CheckinAssignmentRepository assignments, CatalogPort catalog, Clock clock) {
        this.assignments = assignments;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Đòi quyền soát vé cho suất này; trả về phạm vi của người gọi. */
    public TenantScope requireScanner(TenantId organizationId, UUID eventSessionId) {
        TenantScope scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, organizationId);
        requireAssigned(
                TenantContext.has(Permission.CATALOG_MANAGE, organizationId),
                organizationId.value(),
                scope.userId().value(),
                eventSessionId);
        return scope;
    }

    /**
     * @param managesOrganization người gọi quản lý được sự kiện của tổ chức — tách thành tham số để
     *     luật này kiểm được mà không cần dựng cả ngữ cảnh đăng nhập
     */
    public void requireAssigned(boolean managesOrganization, UUID organizationId, UUID userId, UUID eventSessionId) {
        if (managesOrganization) {
            return;
        }
        // Không đọc được suất thuộc sự kiện nào thì KHÔNG cho qua. Khác với bước kiểm giờ mở cổng
        // (catalog im lặng thì bỏ qua): ở đó cái mất là một quy tắc về giờ, còn ở đây cái mất là
        // chính câu trả lời "người này có được phép không".
        UUID eventId = catalog.sessionInfo(eventSessionId)
                .map(CatalogPort.SessionInfo::eventId)
                .orElseThrow(CheckinAccess::unverified);

        List<Assignment> covering = assignments.forUserEvent(organizationId, userId, eventId).stream()
                .filter(row ->
                        row.eventSessionId() == null || row.eventSessionId().equals(eventSessionId))
                .toList();
        if (covering.isEmpty()) {
            throw new ApiException(TicketingErrorCode.NOT_YOUR_EVENT, "Bạn chưa được gán soát vé cho suất này.");
        }

        CatalogPort.EventSchedule schedule;
        try {
            schedule = catalog.eventSchedules(List.of(eventId)).get(eventId);
        } catch (CatalogPort.CatalogUnavailableException e) {
            throw unverified();
        }
        if (schedule == null) {
            throw unverified();
        }
        Instant now = clock.instant();
        boolean active = covering.stream()
                .anyMatch(row -> CheckinAssignments.window(row, schedule).activeAt(now));
        if (!active) {
            throw new ApiException(
                    TicketingErrorCode.CHECKIN_ASSIGNMENT_EXPIRED,
                    "Phân công soát vé cho sự kiện này đã hết hạn. Liên hệ ban tổ chức nếu cần gán lại.");
        }
    }

    private static ApiException unverified() {
        return new ApiException(
                TicketingErrorCode.NOT_YOUR_EVENT,
                "Chưa xác minh được quyền soát vé cho suất này. Thử lại sau ít phút.");
    }
}
