// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * identity-service tự cài {@link MembershipLookup} trực tiếp trên database của nó.
 *
 * <p>Các service khác cài bằng HTTP client gọi {@code /internal/memberships} có cache ngắn — không
 * service nào đọc bảng {@code organization_members} của identity (ADR-1002).
 */
@Component
public class LocalMembershipLookup implements MembershipLookup {

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public LocalMembershipLookup(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Override
    public Principal resolve(String idpSubject) {
        return users.findByIdpSubject(idpSubject)
                .map(user -> new Principal(user.id(), loadMemberships(user.id()), user.superAdmin()))
                .orElse(null);
    }

    private Map<TenantId, Role> loadMemberships(UserId userId) {
        Map<TenantId, Role> result = new HashMap<>();
        jdbc.query(
                "SELECT organization_id, role FROM organization_members WHERE user_id = ?",
                rs -> {
                    result.put(
                            TenantId.of(rs.getObject("organization_id", UUID.class)),
                            Role.valueOf(rs.getString("role")));
                },
                userId.value());
        return result;
    }
}
