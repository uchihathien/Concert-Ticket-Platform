// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.Map;

/**
 * Đặt {@link TenantContext} cho unit test của tầng application.
 *
 * <p>Nằm cùng package với {@link TenantContext} vì {@code set}/{@code clear} cố ý chỉ mở cho
 * {@link TenantFilter}. Lớp này chỉ có ở classpath test: code chạy thật vẫn chỉ có một đường dựng
 * ngữ cảnh là đi qua filter.
 */
public final class TenantContextFixture {

    private TenantContextFixture() {}

    /** Thành viên của đúng một tổ chức, đang thao tác trên tổ chức đó. */
    public static TenantScope member(UserId user, TenantId organization, Role role) {
        return use(new TenantScope(user, Map.of(organization, role), organization, false));
    }

    /** Superadmin — không là thành viên của tổ chức nào (ADR-1010). */
    public static TenantScope superAdmin(UserId user) {
        return use(new TenantScope(user, Map.of(), null, true));
    }

    /** Đã đăng nhập, không thuộc tổ chức nào (khách). */
    public static TenantScope customer(UserId user) {
        return use(new TenantScope(user, Map.of(), null, false));
    }

    public static TenantScope use(TenantScope scope) {
        TenantContext.set(scope);
        return scope;
    }

    /** Gọi ở {@code @AfterEach}: ThreadLocal sống lâu hơn ca test. */
    public static void clear() {
        TenantContext.clear();
    }
}
