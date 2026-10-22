// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.Map;
import java.util.UUID;

/**
 * Đặt người đang đăng nhập cho test gọi thẳng tầng application.
 *
 * <p>Nằm cùng package với {@link TenantContext} vì {@code set} cố ý không public: ở production chỉ
 * TenantFilter được đặt danh tính, từ một JWT đã kiểm chữ ký.
 */
public final class TestTenant {

    private TestTenant() {}

    public static void as(UUID userId, UUID organizationId, Role role) {
        TenantContext.set(new TenantScope(
                UserId.of(userId), Map.of(TenantId.of(organizationId), role), TenantId.of(organizationId), false));
    }

    public static void clear() {
        TenantContext.clear();
    }
}
