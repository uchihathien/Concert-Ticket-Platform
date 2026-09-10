// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;

/** Truy cập {@link TenantScope} của request hiện tại. */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = ThreadLocal.withInitial(TenantScope::anonymous);

    private TenantContext() {}

    static void set(TenantScope scope) {
        CURRENT.set(scope);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static TenantScope current() {
        return CURRENT.get();
    }

    public static TenantScope requireAuthenticated() {
        TenantScope scope = CURRENT.get();
        if (!scope.isAuthenticated()) {
            throw new ApiException(ErrorCode.Common.UNAUTHENTICATED, "Authentication required");
        }
        return scope;
    }

    public static TenantId requireActiveTenant() {
        TenantScope scope = requireAuthenticated();
        if (scope.activeTenant() == null) {
            throw new ApiException(ErrorCode.Common.FORBIDDEN, "No organization context for this request");
        }
        return scope.activeTenant();
    }

    public static void requireSuperAdmin() {
        if (!requireAuthenticated().superAdmin()) {
            throw ApiException.forbidden("Requires SUPER_ADMIN");
        }
    }

    /**
     * Cửa quyền chính của hệ thống: hỏi <b>quyền</b>, không hỏi tên vai trò.
     *
     * <p>Thay cho câu {@code if (role == null || !role.isAtLeastOrgAdmin())} vốn đã bị chép tay
     * sáu lần chỉ riêng trong identity-service. Sáu bản sao là sáu chỗ để một bản lệch đi, và bản
     * lệch là một lỗ hổng phân quyền chứ không phải một lỗi biên dịch.
     *
     * <p><b>Superadmin đi qua được mọi cửa của tổ chức.</b> Luật đó viết tường minh ở đây, đúng một
     * chỗ — thay vì rải thành một câu {@code if (scope.superAdmin()) return;} trong từng handler,
     * nơi nó có thể bị quên và cũng có thể bị thêm nhầm.
     *
     * @param permission quyền cần có; quyền {@code PLATFORM_*} thì dùng
     *     {@link #requirePlatformPermission} vì chúng không gắn với tổ chức nào
     * @param tenant tổ chức đang thao tác
     */
    public static TenantScope requirePermission(Permission permission, TenantId tenant) {
        if (permission.isPlatformScoped()) {
            // Hỏi một quyền của nền tảng kèm tổ chức là một câu hỏi vô nghĩa, và cách duy nhất nó
            // xuất hiện là gõ nhầm. Ném lỗi lập trình thay vì âm thầm trả 403 cho đúng người.
            throw new IllegalArgumentException(permission + " thuộc phạm vi nền tảng, không hỏi kèm tổ chức");
        }
        TenantScope scope = requireAuthenticated();
        if (scope.superAdmin()) {
            return scope;
        }
        Role role = scope.roleIn(tenant);
        if (role == null || !role.can(permission)) {
            throw ApiException.forbidden("Requires permission " + permission);
        }
        return scope;
    }

    /** Quyền phạm vi nền tảng — hiện chỉ {@code SUPER_ADMIN} có, nhưng cửa hỏi quyền chứ không hỏi vai trò. */
    public static TenantScope requirePlatformPermission(Permission permission) {
        TenantScope scope = requireAuthenticated();
        if (scope.superAdmin() && Role.SUPER_ADMIN.can(permission)) {
            return scope;
        }
        throw ApiException.forbidden("Requires permission " + permission);
    }

    /**
     * Chỉ đòi là <b>thành viên</b> của tổ chức, không đòi quyền cụ thể nào.
     *
     * <p>Dùng cho những đường đọc mà mọi thành viên đều được xem — danh sách thành viên, thông tin
     * tổ chức. Trước đây chúng không kiểm gì cả và dựa hoàn toàn vào {@code TenantFilter}; khi bộ
     * lọc đó bị đi vòng (xem {@code TenantPath}) thì không còn lớp nào phía sau, và bảng thành viên
     * của tổ chức khác trả về 200.
     *
     * <p>Một lớp bảo vệ duy nhất là một lớp bảo vệ sẽ hỏng. Câu kiểm này rẻ — dữ liệu đã nằm sẵn
     * trong {@link TenantScope} — nên không có lý do gì để không có nó.
     */
    public static TenantScope requireMember(TenantId tenant) {
        TenantScope scope = requireAuthenticated();
        if (scope.superAdmin() || scope.isMemberOf(tenant)) {
            return scope;
        }
        // 404 chứ không 403: 403 xác nhận tổ chức đó tồn tại, đúng thứ người ngoài không được biết.
        throw ApiException.notFound("Organization");
    }

    /** Có quyền hay không, không ném lỗi — dùng cho đường đọc cần rẽ nhánh thay vì từ chối. */
    public static boolean has(Permission permission, TenantId tenant) {
        TenantScope scope = current();
        if (!scope.isAuthenticated()) {
            return false;
        }
        if (permission.isPlatformScoped()) {
            return scope.superAdmin() && Role.SUPER_ADMIN.can(permission);
        }
        if (scope.superAdmin()) {
            return true;
        }
        Role role = scope.roleIn(tenant);
        return role != null && role.can(permission);
    }
}
