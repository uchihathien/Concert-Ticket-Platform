// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Kho phiếu trong bộ nhớ, cho unit test.
 *
 * <p>Mô phỏng đúng hai tính chất của bản JDBC mà use case dựa vào: một phiếu mở cho mỗi phiên, và
 * nhận phiếu chỉ thành công khi còn WAITING. Tranh chấp đồng thời thật thì để database kiểm.
 */
public class InMemoryHandoffRepository implements HandoffRepository {

    private final Map<UUID, Handoff> rows = new LinkedHashMap<>();

    @Override
    public Handoff openOrExisting(Handoff candidate) {
        Optional<Handoff> open = openBySession(candidate.sessionId());
        if (open.isPresent()) {
            return open.get();
        }
        rows.put(candidate.id(), candidate);
        return candidate;
    }

    @Override
    public Optional<Handoff> openBySession(UUID sessionId) {
        return rows.values().stream()
                .filter(h -> h.sessionId().equals(sessionId) && h.isOpen())
                .findFirst();
    }

    @Override
    public Optional<Handoff> findById(UUID handoffId) {
        return Optional.ofNullable(rows.get(handoffId));
    }

    @Override
    public List<Handoff> queue(UUID mine, SupportIntent intent, int limit, int offset) {
        return search(mine, Set.of(HandoffStatus.WAITING, HandoffStatus.ASSIGNED), intent, null, false, limit, offset);
    }

    @Override
    public List<Handoff> search(
            UUID mine,
            Set<HandoffStatus> statuses,
            SupportIntent intent,
            String query,
            boolean newestFirst,
            int limit,
            int offset) {
        Comparator<Handoff> byTime = Comparator.comparing(Handoff::requestedAt);
        return rows.values().stream()
                .filter(h -> statuses == null || statuses.isEmpty() || statuses.contains(h.status()))
                .filter(h -> intent == null || h.intent() == intent)
                .filter(h -> mine == null || mine.equals(h.assignedAgentId()))
                .filter(h -> query == null
                        || query.isBlank()
                        || h.reason().toLowerCase().contains(query.toLowerCase())
                        || (h.lastQuestion() != null
                                && h.lastQuestion().toLowerCase().contains(query.toLowerCase())))
                .sorted(newestFirst ? byTime.reversed() : byTime)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<Handoff> claim(UUID handoffId, UUID agentId, Instant now) {
        Handoff current = rows.get(handoffId);
        if (current == null || current.status() != HandoffStatus.WAITING) {
            return Optional.empty();
        }
        Handoff assigned = current.assignTo(agentId, now);
        rows.put(handoffId, assigned);
        return Optional.of(assigned);
    }

    @Override
    public void save(Handoff handoff) {
        rows.put(handoff.id(), handoff);
    }

    @Override
    public int closeAbandoned(Instant before, Instant now) {
        int closed = 0;
        for (Handoff h : List.copyOf(rows.values())) {
            if (h.status() == HandoffStatus.WAITING && h.requestedAt().isBefore(before)) {
                rows.put(h.id(), h.resolve(now));
                closed++;
            }
        }
        return closed;
    }
}
