// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.Map;

/**
 * Cổng ra identity-service.
 *
 * <p>identity-service tự cài trực tiếp trên database của nó; các service khác cài bằng HTTP client
 * có cache ngắn. Không service nào đọc bảng {@code organization_members} của identity.
 */
public interface MembershipLookup {

    record Principal(UserId userId, Map<TenantId, Role> memberships, boolean superAdmin) {}

    /**
     * @param idpSubject giá trị claim {@code sub} trong JWT
     */
    Principal resolve(String idpSubject);
}
