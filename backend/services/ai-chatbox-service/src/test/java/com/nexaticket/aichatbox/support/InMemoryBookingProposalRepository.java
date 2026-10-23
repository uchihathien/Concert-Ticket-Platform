// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * Kho đề xuất trong bộ nhớ cho unit test — tái hiện đúng hợp đồng của {@code JdbcBookingProposalRepository},
 * kể cả {@link DuplicateKeyException} khi trùng đề xuất mở (index bộ phận {@code uq_booking_proposal_open}).
 */
public class InMemoryBookingProposalRepository implements BookingProposalRepository {

    private final Map<UUID, BookingProposal> rows = new LinkedHashMap<>();

    @Override
    public synchronized void insert(BookingProposal p) {
        boolean openDuplicate = rows.values().stream()
                .anyMatch(x -> x.status() == BookingProposalStatus.PROPOSED
                        && x.userId().equals(p.userId())
                        && x.eventSessionId().equals(p.eventSessionId())
                        && x.zoneCode().equals(p.zoneCode()));
        if (openDuplicate || rows.containsKey(p.id())) {
            throw new DuplicateKeyException("uq_booking_proposal_open: đã có đề xuất mở cho khu " + p.zoneCode());
        }
        rows.put(p.id(), p);
    }

    @Override
    public synchronized Optional<BookingProposal> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public synchronized int cancelOpen(UUID userId, UUID eventSessionId, String zoneCode, Instant now) {
        int n = 0;
        for (BookingProposal p : rows.values().stream().toList()) {
            if (p.status() == BookingProposalStatus.PROPOSED
                    && p.userId().equals(userId)
                    && p.eventSessionId().equals(eventSessionId)
                    && p.zoneCode().equals(zoneCode)) {
                rows.put(p.id(), withStatus(p, BookingProposalStatus.CANCELLED, null, null));
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized boolean cancel(UUID id, UUID userId, Instant now) {
        BookingProposal p = rows.get(id);
        if (p == null
                || p.status() != BookingProposalStatus.PROPOSED
                || !p.userId().equals(userId)) {
            return false;
        }
        rows.put(id, withStatus(p, BookingProposalStatus.CANCELLED, null, null));
        return true;
    }

    @Override
    public synchronized boolean markConfirmed(UUID id, BookingProposal.Order order, Instant now) {
        BookingProposal p = rows.get(id);
        if (p == null || p.status() != BookingProposalStatus.PROPOSED) {
            return false;
        }
        rows.put(id, withStatus(p, BookingProposalStatus.CONFIRMED, order, now));
        return true;
    }

    @Override
    public synchronized int expireOverdue(Instant now) {
        int n = 0;
        for (BookingProposal p : rows.values().stream().toList()) {
            if (p.status() == BookingProposalStatus.PROPOSED && !p.expiresAt().isAfter(now)) {
                rows.put(p.id(), withStatus(p, BookingProposalStatus.EXPIRED, null, null));
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized List<BookingProposal> recentForSession(UUID chatSessionId, UUID userId, int limit) {
        return rows.values().stream()
                .filter(p ->
                        p.chatSessionId().equals(chatSessionId) && p.userId().equals(userId))
                .sorted(Comparator.comparing(BookingProposal::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Map<BookingProposalStatus, Long> countByStatus() {
        Map<BookingProposalStatus, Long> counts = new EnumMap<>(BookingProposalStatus.class);
        for (BookingProposalStatus s : BookingProposalStatus.values()) {
            counts.put(s, 0L);
        }
        rows.values().forEach(p -> counts.merge(p.status(), 1L, Long::sum));
        return counts;
    }

    private static BookingProposal withStatus(
            BookingProposal p, BookingProposalStatus status, BookingProposal.Order order, Instant confirmedAt) {
        return new BookingProposal(
                p.id(),
                p.userId(),
                p.chatSessionId(),
                p.eventSlug(),
                p.eventSessionId(),
                p.zoneCode(),
                p.quantity(),
                p.unitPriceVnd(),
                p.totalVnd(),
                status,
                order,
                p.expiresAt(),
                p.createdAt(),
                confirmedAt);
    }
}
