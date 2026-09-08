// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.kernel.id.TenantId;
import java.util.List;
import java.util.Optional;

public interface InvitationRepository {

    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findPending(TenantId organizationId);

    void save(Invitation invitation);
}
