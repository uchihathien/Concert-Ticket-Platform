// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

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
}
