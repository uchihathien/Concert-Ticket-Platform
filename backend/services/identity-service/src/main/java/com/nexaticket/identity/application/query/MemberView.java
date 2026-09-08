// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.domain.model.Membership;

public record MemberView(String userId, String role, String joinedAt) {

    public static MemberView from(Membership membership) {
        return new MemberView(
                membership.userId().toString(),
                membership.role().name(),
                membership.joinedAt().toString());
    }
}
