// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang ticketing-service. Không kiểu nào của Ticketing đi quá lớp này. */
@Component
public class TicketingHttpAdapter implements CheckinAssignmentPort {

    private static final ParameterizedTypeReference<List<AssignmentView>> ASSIGNMENTS =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    public TicketingHttpAdapter(
            RestClient.Builder builder,
            @Value("${nexaticket.identity.ticketing-url:http://localhost:8097}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        // Builder ĐƯỢC TIÊM: nhờ vậy lời gọi mang theo bí mật nội bộ (InternalTokenPropagation) khi
        // nó được cấu hình.
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Map<UserId, List<UUID>> byOrganization(TenantId organizationId) {
        Map<UserId, List<UUID>> result = new LinkedHashMap<>();
        for (AssignmentView view : list(organizationId, null)) {
            result.computeIfAbsent(UserId.of(view.userId()), key -> new ArrayList<>())
                    .add(view.eventId());
        }
        return result;
    }

    @Override
    public List<UUID> eventsOf(TenantId organizationId, UserId userId) {
        return list(organizationId, userId).stream()
                .map(AssignmentView::eventId)
                .toList();
    }

    @Override
    public void replace(TenantId organizationId, UserId userId, Collection<UUID> eventIds, UserId assignedBy) {
        try {
            client.put()
                    .uri("/internal/checkin-assignments/{org}/users/{user}", organizationId.value(), userId.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReplaceRequest(List.copyOf(eventIds), assignedBy == null ? null : assignedBy.value()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            throw new AssignmentsUnavailableException("Không ghi được việc gán sự kiện sang ticketing", e);
        }
    }

    private List<AssignmentView> list(TenantId organizationId, UserId userId) {
        try {
            List<AssignmentView> body = client.get()
                    .uri(uri -> {
                        uri.path("/internal/checkin-assignments/{org}");
                        if (userId != null) {
                            uri.queryParam("userId", userId.value());
                        }
                        return uri.build(organizationId.value());
                    })
                    .retrieve()
                    .body(ASSIGNMENTS);
            return body == null ? List.of() : body;
        } catch (RuntimeException e) {
            throw new AssignmentsUnavailableException("Không đọc được việc gán sự kiện từ ticketing", e);
        }
    }

    /** Tên field là hợp đồng với {@code InternalCheckinAssignmentController} của ticketing-service. */
    private record AssignmentView(UUID userId, UUID eventId) {}

    private record ReplaceRequest(List<UUID> eventIds, UUID assignedBy) {}
}
