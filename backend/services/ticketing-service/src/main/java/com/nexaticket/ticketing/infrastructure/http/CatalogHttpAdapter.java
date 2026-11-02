// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.http;

import com.nexaticket.ticketing.domain.model.AssignmentWindow;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang catalog-service. Không kiểu nào của Catalog đi quá lớp này. */
@Component
public class CatalogHttpAdapter implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogHttpAdapter.class);

    /**
     * Giờ diễn hầu như không đổi trong lúc đang soát vé, còn một cửa thì quét vài vé mỗi giây. Một
     * phút là đủ ngắn để ban tổ chức lùi giờ diễn vẫn có hiệu lực gần như ngay.
     */
    private static final Duration TTL = Duration.ofMinutes(1);

    private final RestClient client;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CachedSchedule> schedules = new ConcurrentHashMap<>();

    private static final ParameterizedTypeReference<List<ScheduleResponse>> SCHEDULES =
            new ParameterizedTypeReference<>() {};

    public CatalogHttpAdapter(
            RestClient.Builder builder,
            Clock clock,
            @Value("${nexaticket.ticketing.catalog-url:http://localhost:8091}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Ngắn: lời gọi này nằm trên đường quét vé, và không có nó thì vẫn soát được.
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.clock = clock;
    }

    @Override
    public Optional<SessionInfo> sessionInfo(UUID eventSessionId) {
        Instant now = clock.instant();
        Cached hit = cache.get(eventSessionId);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return Optional.of(hit.info());
        }
        try {
            SessionResponse response = client.get()
                    .uri("/internal/sessions/{id}", eventSessionId)
                    .retrieve()
                    .body(SessionResponse.class);
            if (response == null || response.startsAt() == null || response.eventId() == null) {
                return Optional.empty();
            }
            SessionInfo info =
                    new SessionInfo(response.eventId(), response.eventTitle(), response.startsAt(), response.endsAt());
            cache.put(eventSessionId, new Cached(info, now.plus(TTL)));
            return Optional.of(info);
        } catch (RuntimeException e) {
            // Chỉ lỗi mới không được cache: catalog sống lại thì lần quét kế tiếp phải thấy ngay.
            log.warn("Không đọc được suất diễn {} từ catalog: {}", eventSessionId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Lịch diễn theo sự kiện, gom một lời gọi cho mọi sự kiện chưa có trong cache.
     *
     * <p>Cùng TTL một phút với {@link #sessionInfo}: lượt quét của nhân viên được gán cả sự kiện cần
     * lịch để biết phân công còn hạn không, và một cửa quét vài vé mỗi giây.
     */
    @Override
    public Map<UUID, EventSchedule> eventSchedules(Collection<UUID> eventIds) {
        Instant now = clock.instant();
        Map<UUID, EventSchedule> result = new LinkedHashMap<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID eventId : new LinkedHashSet<>(eventIds)) {
            CachedSchedule hit = schedules.get(eventId);
            if (hit != null && now.isBefore(hit.expiresAt())) {
                result.put(eventId, hit.schedule());
            } else {
                missing.add(eventId);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }
        List<ScheduleResponse> fetched;
        try {
            fetched = client.get()
                    .uri(uri -> uri.path("/internal/events/schedules")
                            .queryParam("eventIds", missing.toArray())
                            .build())
                    .retrieve()
                    .body(SCHEDULES);
        } catch (RuntimeException e) {
            log.warn("Không đọc được lịch diễn từ catalog: {}", e.toString());
            throw new CatalogUnavailableException("catalog không trả lời", e);
        }
        for (ScheduleResponse response : fetched == null ? List.<ScheduleResponse>of() : fetched) {
            EventSchedule schedule = response.toSchedule();
            schedules.put(schedule.eventId(), new CachedSchedule(schedule, now.plus(TTL)));
            result.put(schedule.eventId(), schedule);
        }
        return result;
    }

    private record Cached(SessionInfo info, Instant expiresAt) {}

    private record CachedSchedule(EventSchedule schedule, Instant expiresAt) {}

    /** Tên field là hợp đồng với {@code CatalogViews.EventSchedule} của catalog-service. */
    private record ScheduleResponse(
            UUID eventId, UUID organizationId, String eventTitle, String venueName, List<SessionTime> sessions) {

        EventSchedule toSchedule() {
            return new EventSchedule(
                    eventId,
                    organizationId,
                    eventTitle,
                    venueName,
                    sessions == null
                            ? List.of()
                            : sessions.stream()
                                    .map(s ->
                                            new AssignmentWindow.Session(s.eventSessionId(), s.startsAt(), s.endsAt()))
                                    .toList());
        }
    }

    private record SessionTime(UUID eventSessionId, Instant startsAt, Instant endsAt) {}

    /** Tên field là hợp đồng với {@code InternalCatalogController.SessionResponse}. */
    private record SessionResponse(UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}
}
