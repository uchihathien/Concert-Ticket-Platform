// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
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
    private final ObjectMapper json;

    public TicketingHttpAdapter(
            RestClient.Builder builder,
            ObjectMapper json,
            @Value("${nexaticket.identity.ticketing-url:http://localhost:8097}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        // Builder ĐƯỢC TIÊM: nhờ vậy lời gọi mang theo bí mật nội bộ (InternalTokenPropagation) khi
        // nó được cấu hình.
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.json = json;
    }

    @Override
    public Map<UserId, List<Assignment>> byOrganization(TenantId organizationId) {
        Map<UserId, List<Assignment>> result = new LinkedHashMap<>();
        for (AssignmentView view : list(organizationId, null)) {
            result.computeIfAbsent(UserId.of(view.userId()), key -> new ArrayList<>())
                    .add(view.toAssignment());
        }
        return result;
    }

    @Override
    public List<Assignment> assignmentsOf(TenantId organizationId, UserId userId) {
        return list(organizationId, userId).stream()
                .map(AssignmentView::toAssignment)
                .toList();
    }

    @Override
    public void assign(TenantId organizationId, UserId userId, Collection<Target> targets, UserId assignedBy) {
        List<TargetView> body = targets.stream()
                .map(target -> new TargetView(target.eventId(), target.eventSessionId()))
                .toList();
        try {
            client.put()
                    .uri("/internal/checkin-assignments/{org}/users/{user}", organizationId.value(), userId.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AssignRequest(body, assignedBy == null ? null : assignedBy.value()))
                    .retrieve()
                    .onStatus(TicketingHttpAdapter::isRejection, (request, response) -> {
                        // 400/409/422 là ticketing từ chối NỘI DUNG lệnh gán (sự kiện không thuộc tổ
                        // chức…). Mang mã lỗi của nó về nguyên vẹn để giao diện nói đúng chuyện. 404
                        // KHÔNG nằm trong nhóm này: đó là InternalApiFilter từ chối vì thiếu bí mật nội
                        // bộ — lỗi cấu hình, tức là "không sẵn sàng".
                        throw rejected(response.getBody().readAllBytes());
                    })
                    .toBodilessEntity();
        } catch (AssignmentRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AssignmentsUnavailableException("Không ghi được phân công soát vé sang ticketing", e);
        }
    }

    @Override
    public void revokeActive(TenantId organizationId, UserId userId) {
        try {
            client.delete()
                    .uri(
                            "/internal/checkin-assignments/{org}/users/{user}/active",
                            organizationId.value(),
                            userId.value())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            throw new AssignmentsUnavailableException("Không gỡ được phân công soát vé bên ticketing", e);
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
            throw new AssignmentsUnavailableException("Không đọc được phân công soát vé từ ticketing", e);
        }
    }

    private static boolean isRejection(HttpStatusCode status) {
        int code = status.value();
        return code == 400 || code == 409 || code == 422;
    }

    private AssignmentRejectedException rejected(byte[] body) {
        String code = "VALIDATION_FAILED";
        String detail = "Phân công soát vé không hợp lệ";
        try {
            Map<?, ?> error = json.readValue(body, Map.class);
            if (error.get("code") instanceof String value) {
                code = value;
            }
            if (error.get("detail") instanceof String value) {
                detail = value;
            }
        } catch (IOException ignored) {
            // Thân lỗi không phải JSON: vẫn là một lần từ chối, chỉ thiếu lời giải thích.
        }
        return new AssignmentRejectedException(code, detail);
    }

    /** Tên field là hợp đồng với {@code InternalCheckinAssignmentController} của ticketing-service. */
    private record AssignmentView(
            UUID userId,
            UUID eventId,
            UUID eventSessionId,
            String eventTitle,
            Instant startsAt,
            Instant expiresAt,
            String status) {

        Assignment toAssignment() {
            return new Assignment(
                    eventId, eventSessionId, eventTitle, startsAt, expiresAt, status == null ? "UNKNOWN" : status);
        }
    }

    private record TargetView(UUID eventId, UUID eventSessionId) {}

    private record AssignRequest(List<TargetView> assignments, UUID assignedBy) {}
}
