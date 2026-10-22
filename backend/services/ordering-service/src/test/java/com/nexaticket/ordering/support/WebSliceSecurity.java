// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Danh tính giả cho slice test web: thay identity-service bằng một bảng trong bộ nhớ.
 *
 * <p>Chuỗi filter là bản THẬT của starter-security (BearerToken → TenantFilter → PermissionGuard);
 * chỉ có câu hỏi "subject này là ai, thuộc tổ chức nào" là trả lời từ đây. Subject không đăng ký
 * thì lookup trả {@code null} — đúng như khi identity-service không nhận ra người dùng.
 */
public final class WebSliceSecurity implements MembershipLookup {

    private final Map<String, Map<TenantId, Role>> memberships = new HashMap<>();
    private final Set<String> superAdmins = new HashSet<>();

    /** Đăng ký một người dùng không thuộc tổ chức nào (khách mua vé). */
    public UUID customer() {
        UUID id = UUID.randomUUID();
        memberships.put(id.toString(), Map.of());
        return id;
    }

    public UUID member(UUID organizationId, Role role) {
        UUID id = UUID.randomUUID();
        memberships.put(id.toString(), Map.of(TenantId.of(organizationId), role));
        return id;
    }

    public UUID superAdmin() {
        UUID id = customer();
        superAdmins.add(id.toString());
        return id;
    }

    /** JWT hợp lệ (đã qua giải mã) mang {@code sub} = userId. */
    public static RequestPostProcessor bearer(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("email", userId + "@test.nexaticket.vn"));
    }

    @Override
    public Principal resolve(Claims claims) {
        Map<TenantId, Role> roles = memberships.get(claims.subject());
        if (roles == null) {
            return null;
        }
        return new Principal(
                UserId.of(UUID.fromString(claims.subject())), roles, superAdmins.contains(claims.subject()));
    }
}
