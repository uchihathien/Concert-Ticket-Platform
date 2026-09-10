// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.access;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Vai trò theo architecture-v2/services.md §"Mô hình vai trò".
 *
 * <p>Nằm ở shared-kernel chứ không ở starter-security vì đây là <b>từ vựng nghiệp vụ dùng chung</b>
 * (ubiquitous language), không phải cơ chế bảo mật. Nhờ vậy domain layer dùng được mà không phải
 * phụ thuộc vào tầng hạ tầng.
 *
 * <h2>Vai trò là một cái tên; {@link Permission} mới là thứ được kiểm</h2>
 *
 * <p>Mỗi vai trò khai thẳng tập quyền của nó, và mọi cửa quyền trong hệ thống hỏi <b>quyền</b> chứ
 * không hỏi tên vai trò. Phân biệt đó quan trọng khi thêm vai trò thứ bảy: chỉ phải khai một dòng ở
 * đây, thay vì đi sửa mọi câu {@code if (role == ORG_ADMIN || role == ORG_OWNER)} nằm rải rác — và
 * câu bị bỏ sót sẽ là một lỗ hổng phân quyền, không phải một lỗi biên dịch.
 *
 * <p>{@code ORG_STAFF} trong tài liệu nghiệp vụ chính là {@link #CHECKIN_STAFF} ở đây; tên
 * {@code CHECKIN_STAFF} được giữ vì nó đã nằm trong ràng buộc CHECK của database, trong realm
 * Keycloak và trong dữ liệu đang chạy.
 */
public enum Role {

    /**
     * Nền tảng: tạo/khoá tổ chức, toàn quyền trên tiền (ADR-1010).
     *
     * <p>Cố ý <b>không</b> có quyền phạm vi tổ chức nào trong bảng dưới: superadmin không phải
     * thành viên của tổ chức nào, và đường đi của họ là các route {@code /v1/platform/**}. Việc
     * superadmin vượt qua được cửa quyền của tổ chức là một luật riêng, viết tường minh ở
     * {@code TenantContext}, chứ không phải hệ quả âm thầm của một danh sách quyền dài.
     */
    SUPER_ADMIN(
            Permission.PLATFORM_ORG_MANAGE,
            Permission.PLATFORM_TEMPLATE_MANAGE,
            Permission.PLATFORM_USER_MANAGE,
            Permission.PLATFORM_FINANCE_VIEW,
            Permission.PLATFORM_AUDIT_READ),

    /** Như {@link #ORG_ADMIN}; khác ở chỗ chỉ chủ sở hữu mới phong được chủ sở hữu khác. */
    ORG_OWNER(
            Permission.CATALOG_MANAGE,
            Permission.EVENT_PUBLISH,
            Permission.CHECKIN_SCAN,
            Permission.ORG_METRICS_VIEW,
            Permission.ORG_MEMBERS_MANAGE,
            Permission.ORG_PROFILE_MANAGE,
            Permission.ORG_LIMITS_SET,
            Permission.ORG_SESSION_REVOKE,
            Permission.ORG_AUDIT_READ),

    ORG_ADMIN(
            Permission.CATALOG_MANAGE,
            Permission.EVENT_PUBLISH,
            Permission.CHECKIN_SCAN,
            Permission.ORG_METRICS_VIEW,
            Permission.ORG_MEMBERS_MANAGE,
            Permission.ORG_PROFILE_MANAGE,
            Permission.ORG_LIMITS_SET,
            Permission.ORG_SESSION_REVOKE,
            Permission.ORG_AUDIT_READ),

    /**
     * Dựng và bán sự kiện, không đụng tới người và không đụng tới tiền.
     *
     * <p>Không có {@code ORG_MEMBERS_MANAGE}: người dựng sự kiện không cần thêm được người vào tổ
     * chức, và cho họ quyền đó là cho họ tự nâng quyền qua trung gian.
     */
    EVENT_MANAGER(
            Permission.CATALOG_MANAGE, Permission.EVENT_PUBLISH, Permission.CHECKIN_SCAN, Permission.ORG_METRICS_VIEW),

    /** Nhân viên soát vé ({@code ORG_STAFF} trong tài liệu nghiệp vụ). Chỉ quét vé vào cửa. */
    CHECKIN_STAFF(Permission.CHECKIN_SCAN),

    /** Khách mua vé. Không có quyền nào thuộc phạm vi tổ chức. */
    CUSTOMER();

    private final Set<Permission> permissions;

    Role(Permission... granted) {
        this.permissions = granted.length == 0
                ? Collections.unmodifiableSet(EnumSet.noneOf(Permission.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(java.util.Arrays.asList(granted)));
    }

    /** Vai trò gắn với một tổ chức cụ thể. */
    public boolean isOrganizationScoped() {
        return this != SUPER_ADMIN && this != CUSTOMER;
    }

    /** Tập quyền của vai trò này. Không sửa được — ma trận là hằng số của hệ thống. */
    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    /**
     * @deprecated Hỏi quyền thay vì hỏi tên vai trò: {@code can(Permission.ORG_MEMBERS_MANAGE)}.
     *     Giữ lại để những chỗ gọi cũ không gãy; nó đã được định nghĩa lại theo ma trận nên hai
     *     đường luôn trả lời giống nhau.
     */
    @Deprecated
    public boolean isAtLeastOrgAdmin() {
        return can(Permission.ORG_MEMBERS_MANAGE);
    }

    /** @deprecated Dùng {@code can(Permission.CATALOG_MANAGE)}. */
    @Deprecated
    public boolean canManageCatalog() {
        return can(Permission.CATALOG_MANAGE);
    }

    /** @deprecated Dùng {@code can(Permission.CHECKIN_SCAN)}. */
    @Deprecated
    public boolean canCheckIn() {
        return can(Permission.CHECKIN_SCAN);
    }
}
