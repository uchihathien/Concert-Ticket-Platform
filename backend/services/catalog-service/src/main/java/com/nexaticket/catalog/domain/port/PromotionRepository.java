// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.Promotion;
import java.util.Optional;
import java.util.UUID;

public interface PromotionRepository {

    Optional<Promotion> findByCode(UUID organizationId, String code);

    void save(Promotion promotion);
}
