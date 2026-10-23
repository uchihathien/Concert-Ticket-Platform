// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.port.SeatAvailabilityPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Số chỗ còn theo khu, từ {@code GET /internal/sessions/{id}/seat-status} của inventory.
 *
 * <p>Đường nội bộ, nên {@code X-Internal-Token} được gắn tự động bởi {@code InternalTokenPropagation}
 * — client dựng từ builder được tiêm. Đọc số đếm, không đọc từng ghế: chip chỉ cần "còn bao nhiêu".
 */
@Component
public class SeatAvailabilityHttpAdapter implements SeatAvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityHttpAdapter.class);

    private final RestClient client;

    public SeatAvailabilityHttpAdapter(@Qualifier("inventoryClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Map<String, Integer> availableByZone(UUID eventSessionId) {
        try {
            SeatStatus status = client.get()
                    .uri("/internal/sessions/{eventSessionId}/seat-status", eventSessionId)
                    .retrieve()
                    .body(SeatStatus.class);
            Map<String, Integer> out = new LinkedHashMap<>();
            if (status != null && status.zones() != null) {
                for (SeatStatus.Zone zone : status.zones()) {
                    out.put(zone.zoneCode(), zone.available());
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Không đọc được số chỗ còn của suất {}: {}", eventSessionId, e.getMessage());
            return Map.of();
        }
    }

    /** Phải khớp {@code InternalSeatStatusController.SeatStatusResponse} của inventory, tên từng field. */
    record SeatStatus(UUID eventSessionId, List<Zone> zones) {
        record Zone(String zoneCode, int available) {}
    }
}
