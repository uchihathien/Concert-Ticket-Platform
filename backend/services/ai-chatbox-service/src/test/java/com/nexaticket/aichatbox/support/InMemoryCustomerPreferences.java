// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.CustomerPreferences;
import com.nexaticket.aichatbox.domain.port.CustomerPreferencesRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Kho sở thích trong bộ nhớ cho test không có database. */
public class InMemoryCustomerPreferences implements CustomerPreferencesRepository {

    public final Map<UUID, CustomerPreferences> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<CustomerPreferences> find(UUID userId) {
        return Optional.ofNullable(rows.get(userId));
    }

    @Override
    public void save(CustomerPreferences preferences) {
        rows.put(preferences.userId(), preferences);
    }

    @Override
    public boolean delete(UUID userId) {
        return rows.remove(userId) != null;
    }
}
