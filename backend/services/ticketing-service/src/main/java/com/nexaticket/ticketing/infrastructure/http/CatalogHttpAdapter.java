// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.http;

import com.nexaticket.ticketing.domain.port.CatalogPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private record Cached(SessionInfo info, Instant expiresAt) {}

    /** Tên field là hợp đồng với {@code InternalCatalogController.SessionResponse}. */
    private record SessionResponse(UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}
}
