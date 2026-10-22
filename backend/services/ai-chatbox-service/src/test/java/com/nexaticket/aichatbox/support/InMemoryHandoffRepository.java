// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoverNote;
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

    /** Cùng điều kiện với bản Jdbc: chỉ ghi khi trạng thái và người nhận vẫn như lúc người gọi đọc. */
    @Override
    public synchronized boolean transition(
            UUID handoffId, HandoffStatus expectedStatus, UUID expectedAgentId, Handoff next) {
        Handoff current = rows.get(handoffId);
        if (current == null
                || current.status() != expectedStatus
                || !java.util.Objects.equals(current.assignedAgentId(), expectedAgentId)) {
            return false;
        }
        rows.put(handoffId, next);
        return true;
    }

    /** Không có khoá trong bộ nhớ — test đơn luồng; khoá thật được kiểm ở HandoffTransitionRaceIT. */
    @Override
    public Optional<Handoff> lockById(UUID handoffId) {
        return findById(handoffId);
    }

    /** Ghi chú bàn giao không lưu ở bản in-memory — {@code details} JSON được kiểm ở StaleHandoffReleaseIT. */
    @Override
    public synchronized boolean handover(
            UUID handoffId,
            HandoffStatus expectedStatus,
            UUID expectedAgent,
            UUID newAgent,
            Instant now,
            HandoverNote note) {
        Handoff current = rows.get(handoffId);
        if (current == null
                || current.status() != expectedStatus
                || !java.util.Objects.equals(current.assignedAgentId(), expectedAgent)) {
            return false;
        }
        rows.put(handoffId, withHolder(current, newAgent, now));
        return true;
    }

    /**
     * Bản in-memory không có {@code chat_messages} nên chỉ xét {@code assignedAt}; điều kiện "có tin
     * AGENT gần đây" được kiểm ở StaleHandoffReleaseIT.
     */
    @Override
    public synchronized int releaseStaleAssigned(Instant before, Instant now) {
        int released = 0;
        for (Handoff h : List.copyOf(rows.values())) {
            if (h.status() == HandoffStatus.ASSIGNED && h.assignedAt().isBefore(before)) {
                rows.put(h.id(), withHolder(h, null, now));
                released++;
            }
        }
        return released;
    }

    private static Handoff withHolder(Handoff h, UUID agent, Instant now) {
        return new Handoff(
                h.id(),
                h.sessionId(),
                h.userId(),
                agent == null ? HandoffStatus.WAITING : HandoffStatus.ASSIGNED,
                h.trigger(),
                h.intent(),
                h.reason(),
                h.details(),
                h.lastQuestion(),
                agent,
                h.requestedAt(),
                agent == null ? null : now,
                null);
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
