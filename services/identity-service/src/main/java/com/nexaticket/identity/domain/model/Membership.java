// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.model;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.UUID;

/** Entity bên trong aggregate {@link Organization}. */
public record Membership(UUID id, UserId userId, Role role, Instant joinedAt) {

    public Membership withRole(Role newRole) {
        return new Membership(id, userId, newRole, joinedAt);
    }
}
