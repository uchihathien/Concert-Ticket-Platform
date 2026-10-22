// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import static com.nexaticket.identity.infrastructure.http.FakeHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.AssignmentsUnavailableException;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Anti-Corruption Layer sang ticketing-service: tên field là hợp đồng với
 * {@code InternalCheckinAssignmentController} bên đó, và mọi lỗi đều phải thành "không sẵn sàng" để
 * tầng trên báo "thử lại sau" thay vì 500.
 */
class TicketingHttpAdapterTest {

    private static final TenantId ORG = TenantId.of(UUID.fromString("11111111-1111-4111-a111-111111111111"));
    private static final UserId STAFF = UserId.of(UUID.fromString("33333333-3333-4333-a333-333333333333"));
    private static final UserId ADMIN = UserId.of(UUID.fromString("44444444-4444-4444-a444-444444444444"));

    private final ObjectMapper json = new ObjectMapper();
    private final FakeHttpServer ticketing = new FakeHttpServer();
    private final TicketingHttpAdapter adapter = new TicketingHttpAdapter(ticketing.builder(), "http://ticketing.test");

    @Test
    @DisplayName("gom việc gán theo người, giữ thứ tự sự kiện")
    void gom_theo_nguoi() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ticketing.respondWith(call -> respond(
                HttpStatus.OK,
                "[{\"userId\":\"%s\",\"eventId\":\"%s\"},{\"userId\":\"%s\",\"eventId\":\"%s\"},{\"userId\":\"%s\",\"eventId\":\"%s\"}]"
                        .formatted(STAFF.value(), e1, other, e3, STAFF.value(), e2)));

        Map<UserId, List<UUID>> byUser = adapter.byOrganization(ORG);

        assertThat(byUser.get(STAFF)).containsExactly(e1, e2);
        assertThat(byUser.get(UserId.of(other))).containsExactly(e3);
        FakeHttpServer.Call call = ticketing.calls().get(0);
        assertThat(call.path()).isEqualTo("/internal/checkin-assignments/" + ORG.value());
        assertThat(call.uri().getQuery()).isNull();
    }

    @Test
    @DisplayName("sự kiện của một người: lọc bằng tham số userId")
    void su_kien_cua_mot_nguoi() {
        ticketing.respondWith(call -> respond(HttpStatus.OK, "[]"));

        assertThat(adapter.eventsOf(ORG, STAFF)).isEmpty();

        assertThat(ticketing.calls().get(0).uri().getQuery()).isEqualTo("userId=" + STAFF.value());
    }

    @Test
    @DisplayName("đặt lại danh sách: PUT đúng người, thân gồm eventIds và người gán")
    void dat_lai() throws Exception {
        UUID event = UUID.randomUUID();
        ticketing.respondWith(call -> respond(HttpStatus.NO_CONTENT, ""));

        adapter.replace(ORG, STAFF, List.of(event), ADMIN);

        FakeHttpServer.Call call =
                ticketing.callsTo(HttpMethod.PUT, "/internal/").get(0);
        assertThat(call.path()).isEqualTo("/internal/checkin-assignments/" + ORG.value() + "/users/" + STAFF.value());
        JsonNode body = json.readTree(call.body());
        assertThat(body.get("eventIds").get(0).asText()).isEqualTo(event.toString());
        assertThat(body.get("assignedBy").asText()).isEqualTo(ADMIN.value().toString());
    }

    @Test
    @DisplayName("gỡ hết (danh sách rỗng, không ai gán) gửi mảng rỗng và assignedBy null")
    void go_het() throws Exception {
        ticketing.respondWith(call -> respond(HttpStatus.NO_CONTENT, ""));

        adapter.replace(ORG, STAFF, List.of(), null);

        JsonNode body = json.readTree(ticketing.calls().get(0).body());
        assertThat(body.get("eventIds")).isEmpty();
        assertThat(body.get("assignedBy").isNull()).isTrue();
    }

    @Test
    @DisplayName("ticketing trả 5xx, 404 nội bộ hay mất kết nối đều thành AssignmentsUnavailableException")
    void loi_thanh_khong_san_sang() {
        ticketing.respondWith(call -> respond(HttpStatus.SERVICE_UNAVAILABLE, ""));
        assertThatThrownBy(() -> adapter.byOrganization(ORG)).isInstanceOf(AssignmentsUnavailableException.class);
        assertThatThrownBy(() -> adapter.replace(ORG, STAFF, List.of(), null))
                .isInstanceOf(AssignmentsUnavailableException.class);

        // 404 là phản hồi có chủ ý của InternalApiFilter khi thiếu X-Internal-Token.
        ticketing.respondWith(call -> respond(HttpStatus.NOT_FOUND, ""));
        assertThatThrownBy(() -> adapter.eventsOf(ORG, STAFF)).isInstanceOf(AssignmentsUnavailableException.class);

        ticketing.respondWith(call -> null);
        assertThatThrownBy(() -> adapter.eventsOf(ORG, STAFF)).isInstanceOf(AssignmentsUnavailableException.class);
    }
}
