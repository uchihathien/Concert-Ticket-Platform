// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.Map;
import java.util.Set;

/**
 * Ngữ cảnh đã xác thực của một request.
 *
 * <p>ADR-0008: tenant lấy từ membership đã xác thực, <b>không</b> từ body hay query {@code
 * organization_id} do client gửi.
 *
 * @param memberships tổ chức mà người dùng thuộc về, kèm vai trò trong từng tổ chức
 * @param activeTenant tổ chức đang thao tác; null với route không thuộc phạm vi tổ chức
 */
public record TenantScope(UserId userId, Map<TenantId, Role> memberships, TenantId activeTenant, boolean superAdmin) {

    public TenantScope {
        memberships = Map.copyOf(memberships);
    }

    public static TenantScope anonymous() {
        return new TenantScope(null, Map.of(), null, false);
    }

    public boolean isAuthenticated() {
        return userId != null;
    }

    public boolean isMemberOf(TenantId tenant) {
        return memberships.containsKey(tenant);
    }

    public Role roleIn(TenantId tenant) {
        return memberships.get(tenant);
    }

    public Set<TenantId> tenants() {
        return memberships.keySet();
    }

    /** Bản sao đã chọn tổ chức đang thao tác. Chỉ gọi sau khi đã verify membership. */
    public TenantScope withActiveTenant(TenantId tenant) {
        return new TenantScope(userId, memberships, tenant, superAdmin);
    }
}
