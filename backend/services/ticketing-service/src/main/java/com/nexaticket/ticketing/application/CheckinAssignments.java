// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc và đặt việc gán nhân viên soát vé vào sự kiện. */
@Service
public class CheckinAssignments {

    private final CheckinAssignmentRepository repository;
    private final CatalogPort catalog;
    private final Clock clock;
    private final Duration fallbackDuration;

    public CheckinAssignments(
            CheckinAssignmentRepository repository,
            CatalogPort catalog,
            Clock clock,
            @Value("${nexaticket.ticketing.checkin.fallback-session-duration:PT6H}") Duration fallbackDuration) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
        this.fallbackDuration = fallbackDuration;
    }

    public record Assigned(UUID userId, UUID eventId) {}

    /**
     * @param lastSessionEndsAt kết thúc của suất cuối trong mọi sự kiện được gán; {@code null} khi
     *     các sự kiện đó chưa có suất nào. Suất không khai giờ kết thúc tính bằng giờ bắt đầu cộng
     *     thời lượng mặc định — cùng quy ước với giờ đóng cổng.
     */
    public record Coverage(UUID userId, List<UUID> eventIds, Instant lastSessionEndsAt) {}

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

    /**
     * Suất cuối của từng nhân viên trong một tổ chức — identity-service dùng để tự khoá tài khoản
     * nhân viên đã hết việc.
     *
     * <p>Catalog không trả lời thì báo lỗi chứ không trả danh sách thiếu: một nhân viên "không có
     * suất nào" trong mắt người gọi sẽ không bị khoá, nhưng một nhân viên có mốc sai thì có thể bị
     * khoá giữa ca.
     */
    @Transactional(readOnly = true)
    public List<Coverage> coverage(UUID organizationId) {
        Map<UUID, List<UUID>> eventsByUser = new LinkedHashMap<>();
        Set<UUID> allEvents = new LinkedHashSet<>();
        for (var assigned : repository.find(organizationId, null)) {
            eventsByUser
                    .computeIfAbsent(assigned.userId(), key -> new java.util.ArrayList<>())
                    .add(assigned.eventId());
            allEvents.add(assigned.eventId());
        }
        if (eventsByUser.isEmpty()) {
            return List.of();
        }
        List<CatalogPort.SessionSlot> sessions = catalog.sessionsOfEvents(allEvents)
                .orElseThrow(() -> new ApiException(
                        TicketingErrorCode.CATALOG_UNAVAILABLE, "Chưa đọc được lịch suất diễn. Thử lại sau."));
        Map<UUID, Instant> lastEndByEvent = new LinkedHashMap<>();
        for (CatalogPort.SessionSlot slot : sessions) {
            Instant end =
                    slot.endsAt() != null ? slot.endsAt() : slot.startsAt().plus(fallbackDuration);
            lastEndByEvent.merge(slot.eventId(), end, (a, b) -> a.isAfter(b) ? a : b);
        }
        return eventsByUser.entrySet().stream()
                .map(entry -> new Coverage(
                        entry.getKey(),
                        List.copyOf(entry.getValue()),
                        entry.getValue().stream()
                                .map(lastEndByEvent::get)
                                .filter(java.util.Objects::nonNull)
                                .max(Instant::compareTo)
                                .orElse(null)))
                .toList();
    }
}
