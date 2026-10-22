// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ai được soát vé của suất nào — kiểm ở <b>mọi</b> lượt quét, không dựa vào việc app ẩn hiện gì.
 *
 * <p>Hai đường vào, và chỉ hai:
 *
 * <ul>
 *   <li>Người quản lý được sự kiện của tổ chức ({@link Permission#CATALOG_MANAGE} — chủ tổ chức,
 *       quản trị, quản lý sự kiện): soát mọi sự kiện <b>của tổ chức mình</b>, không cần được gán.
 *   <li>Người chỉ có {@link Permission#CHECKIN_SCAN} (nhân viên soát vé): chỉ những sự kiện ban tổ
 *       chức đã gán cho họ.
 * </ul>
 *
 * <p>"Của tổ chức mình" không kiểm ở đây mà ở {@code CheckInHandler}: vé phải thuộc tổ chức trên
 * đường dẫn, nên người của tổ chức khác không soát được dù lọt qua bước này.
 */
@Component
public class CheckinAccess {

    private static final Logger log = LoggerFactory.getLogger(CheckinAccess.class);

    private final CheckinAssignmentRepository assignments;
    private final CatalogPort catalog;
    private final CheckinShifts shifts;
    private final Clock clock;

    public CheckinAccess(
            CheckinAssignmentRepository assignments, CatalogPort catalog, CheckinShifts shifts, Clock clock) {
        this.assignments = assignments;
        this.catalog = catalog;
        this.shifts = shifts;
        this.clock = clock;
    }

    /**
     * Phạm vi của một lượt QUÉT.
     *
     * @param shiftGate cổng của ca đang trực — ghi vào lượt quét thay cho thứ máy gửi lên; {@code null}
     *     với người quản lý sự kiện (không bị ràng buộc ca)
     */
    public record ScannerScope(TenantScope scope, String shiftGate) {}

    /**
     * Như {@link #requireScanner}, cộng thêm ràng buộc CA cho nhân viên chỉ có vai soát vé: phải có
     * phiên ca đang mở ở một ca của đúng suất này, và đang trong khung giờ của ca (sớm tối đa 30 phút).
     *
     * <p>Dùng cho mọi đường GHI một lượt quét (quét, đồng bộ offline, cho vào bằng tra cứu). Đường
     * chỉ đọc (lịch sử) vẫn là {@link #requireScanner}: xem lại sau ca không phải là soát vé.
     *
     * @param at thời điểm của lượt quét — lượt quét offline đồng bộ muộn thì là lúc máy quét, không
     *     phải lúc gửi lên
     */
    public ScannerScope requireScannerOnShift(TenantId organizationId, UUID eventSessionId, Instant at) {
        TenantScope scope = requireScanner(organizationId, eventSessionId);
        if (TenantContext.has(Permission.CATALOG_MANAGE, organizationId)) {
            return new ScannerScope(scope, null);
        }
        Instant when = at != null ? at : clock.instant();
        // Lượt quét đã xảy ra (offline): tìm ca bao trùm thời điểm quét, kể cả khi ca đã đóng.
        var covering = at != null
                ? shifts.shiftCovering(organizationId.value(), scope.userId().value(), eventSessionId, when)
                : shifts.activeShift(organizationId.value(), scope.userId().value(), eventSessionId, when);
        return covering.map(shift -> new ScannerScope(scope, shift.gateName())).orElseThrow(() -> {
            log.warn(
                    "Từ chối SHIFT_NOT_ACTIVE: nhân viên {} quét suất {} lúc {} khi không trong ca",
                    scope.userId().value(),
                    eventSessionId,
                    when);
            return new ApiException(
                    TicketingErrorCode.SHIFT_NOT_ACTIVE, "Bạn chưa bắt đầu ca trực của suất này, hoặc ca đã kết thúc.");
        });
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
                .orElseThrow(() -> new ApiException(
                        TicketingErrorCode.NOT_YOUR_EVENT,
                        "Chưa xác minh được quyền soát vé cho suất này. Thử lại sau ít phút."));
        if (!assignments.isAssigned(organizationId, userId, eventId)) {
            throw new ApiException(TicketingErrorCode.NOT_YOUR_EVENT, "Bạn chưa được gán soát vé cho sự kiện này.");
        }
    }
}
