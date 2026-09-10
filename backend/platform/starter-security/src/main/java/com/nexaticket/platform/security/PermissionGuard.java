// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantPath;
import com.nexaticket.platform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Thực thi {@link RequiresPermission}.
 *
 * <p>Chạy sau {@code TenantFilter} — filter dựng {@code TenantScope} và đã trả 404 cho người không
 * thuộc tổ chức, nên tới đây chỉ còn câu hỏi về quyền.
 *
 * <p>Ném {@code ApiException} chứ không tự ghi response: {@code GlobalExceptionHandler} biến nó
 * thành RFC 7807 giống mọi lỗi khác. Một interceptor tự dựng JSON là một định dạng lỗi thứ hai mà
 * frontend phải biết.
 */
public class PermissionGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (required == null) {
            return true;
        }

        Permission permission = required.value();
        if (permission.isPlatformScoped()) {
            TenantContext.requirePlatformPermission(permission);
            return true;
        }

        // Dùng chung cài đặt với TenantFilter. Hai bản sao của cùng một phép đọc đường dẫn là hai
        // bản sẽ lệch nhau, và bản lệch là một lần vượt rào — xem TenantPath.
        TenantPath.Match match = TenantPath.organizationOf(request.getRequestURI());
        if (match.tenant() == null) {
            // Khai một quyền của tổ chức trên route không mang tổ chức nào là lỗi cấu hình, không
            // phải lỗi của người gọi. Từ chối tường minh: cho qua sẽ biến một endpoint tưởng là
            // đã bảo vệ thành một endpoint mở.
            throw ApiException.forbidden("Quyền " + permission + " cần tổ chức trên đường dẫn /organizations/{id}");
        }
        TenantContext.requirePermission(permission, match.tenant());
        return true;
    }
}
