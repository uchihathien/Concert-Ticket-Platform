// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.domain.model.Invitation;
import java.time.Instant;

/** Invitation metadata for its recipient. It intentionally never includes the raw token or token hash. */
public record MyInvitationView(
        String id, String organizationId, String organizationName, String role, String expiresAt, boolean expired) {

    public static MyInvitationView from(Invitation invitation, String organizationName, Instant now) {
        return new MyInvitationView(
                invitation.id().toString(),
                invitation.organizationId().toString(),
                organizationName,
                invitation.role().name(),
                invitation.expiresAt().toString(),
                now.isAfter(invitation.expiresAt()));
    }
}
