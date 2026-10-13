// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionDirectoryQuery {

    private final SessionDirectory.Reader reader;

    public SessionDirectoryQuery(SessionDirectory.Reader reader) {
        this.reader = reader;
    }

    @Transactional(readOnly = true)
    public SessionDirectory.Listing forOrganization(UUID organizationId) {
        return new SessionDirectory.Listing(reader.sessionsOf(organizationId));
    }
}
