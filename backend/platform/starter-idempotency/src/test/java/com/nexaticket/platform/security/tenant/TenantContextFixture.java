// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.id.UserId;
import java.util.Map;

/**
 * Đặt {@link TenantContext} cho test của starter-idempotency.
 *
 * <p>Nằm cùng package với {@link TenantContext} vì {@code set}/{@code clear} cố ý chỉ mở cho
 * {@link TenantFilter}. Chỉ có ở classpath test của module này.
 */
public final class TenantContextFixture {

    private TenantContextFixture() {}

    /** Đã đăng nhập, không thuộc tổ chức nào (khách mua vé). */
    public static TenantScope customer(UserId user) {
        TenantScope scope = new TenantScope(user, Map.of(), null, false);
        TenantContext.set(scope);
        return scope;
    }

    /** Gọi ở {@code @AfterEach}: ThreadLocal sống lâu hơn ca test. */
    public static void clear() {
        TenantContext.clear();
    }
}
