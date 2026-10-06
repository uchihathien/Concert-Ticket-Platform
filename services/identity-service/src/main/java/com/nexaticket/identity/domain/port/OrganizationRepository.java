// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.List;
import java.util.Optional;

/** Cổng ra persistence. Chỉ nhận và trả aggregate, không trả DTO. */
public interface OrganizationRepository {

    Optional<Organization> findById(TenantId id);

    boolean slugExists(Slug slug);

    List<Organization> findAllByMember(UserId userId);

    List<Organization> findAll(int limit, int offset);

    void save(Organization organization);
}
