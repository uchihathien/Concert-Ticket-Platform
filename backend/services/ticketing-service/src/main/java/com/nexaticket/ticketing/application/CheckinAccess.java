// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
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
 *   <li>Người chỉ có {@link Permission#CHECKIN_SCAN} (nhân viên soát vé): chỉ những sự kiện ban tổ
 *       chức đã gán cho họ.
 * </ul>
 *
 * <p>"Của tổ chức mình" không kiểm ở đây mà ở {@code CheckInHandler}: vé phải thuộc tổ chức trên
 * đường dẫn, nên người của tổ chức khác không soát được dù lọt qua bước này.
 */
@Component
public class CheckinAccess {

    private final CheckinAssignmentRepository assignments;
    private final CatalogPort catalog;

    public CheckinAccess(CheckinAssignmentRepository assignments, CatalogPort catalog) {
        this.assignments = assignments;
        this.catalog = catalog;
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
