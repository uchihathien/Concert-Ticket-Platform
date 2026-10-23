// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.CustomerPreferences;
import java.util.Optional;
import java.util.UUID;

/** Kho sở thích của khách — một dòng mỗi khách. */
public interface CustomerPreferencesRepository {

    Optional<CustomerPreferences> find(UUID userId);

    /** Ghi đè toàn bộ dòng của khách (gộp đã làm ở use case). */
    void save(CustomerPreferences preferences);

    /** @return {@code true} nếu khách có dòng và dòng đã bị xoá */
    boolean delete(UUID userId);
}
