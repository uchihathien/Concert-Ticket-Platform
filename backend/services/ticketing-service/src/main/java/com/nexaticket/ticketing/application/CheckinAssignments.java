// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc và đặt việc gán nhân viên soát vé vào sự kiện. */
@Service
public class CheckinAssignments {

    private final CheckinAssignmentRepository repository;
    private final Clock clock;

    public CheckinAssignments(CheckinAssignmentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record Assigned(UUID userId, UUID eventId) {}

    @Transactional(readOnly = true)
    public List<Assigned> list(UUID organizationId, UUID userId) {
        return repository.find(organizationId, userId).stream()
                .map(a -> new Assigned(a.userId(), a.eventId()))
                .toList();
    }

    @Transactional
    public List<Assigned> replace(UUID organizationId, UUID userId, Collection<UUID> eventIds, UUID assignedBy) {
        repository.replace(organizationId, userId, eventIds, assignedBy, clock.instant());
        return list(organizationId, userId);
    }
}
