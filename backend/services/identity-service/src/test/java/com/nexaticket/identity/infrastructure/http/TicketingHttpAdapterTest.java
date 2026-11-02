// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import static com.nexaticket.identity.infrastructure.http.FakeHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.Assignment;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.AssignmentRejectedException;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.AssignmentsUnavailableException;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.Target;
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
    private final TicketingHttpAdapter adapter =
            new TicketingHttpAdapter(ticketing.builder(), json, "http://ticketing.test");

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

        Map<UserId, List<Assignment>> byUser = adapter.byOrganization(ORG);

        assertThat(byUser.get(STAFF)).extracting(Assignment::eventId).containsExactly(e1, e2);
        assertThat(byUser.get(UserId.of(other))).extracting(Assignment::eventId).containsExactly(e3);
        // ticketing cũ chưa trả trạng thái: coi là chưa biết, KHÔNG coi là hết hạn.
        assertThat(byUser.get(STAFF)).allMatch(Assignment::active);
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
    @DisplayName("đọc trạng thái và khung giờ phân công; chỉ sự kiện còn hiệu lực vào eventsOf")
    void doc_trang_thai() {
        UUID active = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        ticketing.respondWith(call -> respond(
                HttpStatus.OK,
                ("[{\"userId\":\"%s\",\"eventId\":\"%s\",\"eventSessionId\":\"%s\",\"eventTitle\":\"Đêm nhạc\","
                                + "\"startsAt\":\"2026-12-01T12:00:00Z\",\"expiresAt\":\"2026-12-01T21:00:00Z\",\"status\":\"UPCOMING\"},"
                                + "{\"userId\":\"%s\",\"eventId\":\"%s\",\"status\":\"EXPIRED\"}]")
                        .formatted(STAFF.value(), active, session, STAFF.value(), expired)));

        List<Assignment> mine = adapter.assignmentsOf(ORG, STAFF);

        assertThat(mine.get(0).eventSessionId()).isEqualTo(session);
        assertThat(mine.get(0).eventTitle()).isEqualTo("Đêm nhạc");
        assertThat(mine.get(0).expiresAt()).hasToString("2026-12-01T21:00:00Z");
        assertThat(adapter.eventsOf(ORG, STAFF)).containsExactly(active);
    }

    @Test
    @DisplayName("gán: PUT đúng người, thân gồm từng mục (sự kiện, suất) và người gán")
    void dat_lai() throws Exception {
        UUID event = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        ticketing.respondWith(call -> respond(HttpStatus.NO_CONTENT, ""));

        adapter.assign(ORG, STAFF, List.of(new Target(event, null), new Target(event, session)), ADMIN);

        FakeHttpServer.Call call =
                ticketing.callsTo(HttpMethod.PUT, "/internal/").get(0);
        assertThat(call.path()).isEqualTo("/internal/checkin-assignments/" + ORG.value() + "/users/" + STAFF.value());
        JsonNode body = json.readTree(call.body());
        assertThat(body.get("assignments").get(0).get("eventId").asText()).isEqualTo(event.toString());
        assertThat(body.get("assignments").get(0).get("eventSessionId").isNull())
                .isTrue();
        assertThat(body.get("assignments").get(1).get("eventSessionId").asText())
                .isEqualTo(session.toString());
        assertThat(body.get("assignedBy").asText()).isEqualTo(ADMIN.value().toString());
    }

    @Test
    @DisplayName("ticketing từ chối nội dung (400) thì mang mã lỗi của nó về, không gộp vào 'không sẵn sàng'")
    void tu_choi_noi_dung() {
        ticketing.respondWith(call -> respond(
                HttpStatus.BAD_REQUEST,
                "{\"code\":\"EVENT_NOT_IN_ORGANIZATION\",\"detail\":\"Sự kiện không thuộc tổ chức này\"}"));

        assertThatThrownBy(() -> adapter.assign(ORG, STAFF, List.of(new Target(UUID.randomUUID(), null)), ADMIN))
                .isInstanceOf(AssignmentRejectedException.class)
                .hasMessage("Sự kiện không thuộc tổ chức này")
                .extracting(e -> ((AssignmentRejectedException) e).code())
                .isEqualTo("EVENT_NOT_IN_ORGANIZATION");
    }

    @Test
    @DisplayName("gỡ phân công còn hiệu lực: DELETE …/users/{id}/active")
    void go_phan_cong_con_hieu_luc() {
        ticketing.respondWith(call -> respond(HttpStatus.NO_CONTENT, ""));

        adapter.revokeActive(ORG, STAFF);

        FakeHttpServer.Call call =
                ticketing.callsTo(HttpMethod.DELETE, "/internal/").get(0);
        assertThat(call.path())
                .isEqualTo("/internal/checkin-assignments/" + ORG.value() + "/users/" + STAFF.value() + "/active");
    }

    @Test
    @DisplayName("gỡ hết (danh sách rỗng, không ai gán) gửi mảng rỗng và assignedBy null")
    void go_het() throws Exception {
        ticketing.respondWith(call -> respond(HttpStatus.NO_CONTENT, ""));

        adapter.replace(ORG, STAFF, List.of(), null);

        JsonNode body = json.readTree(ticketing.calls().get(0).body());
        assertThat(body.get("assignments")).isEmpty();
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
        assertThatThrownBy(() -> adapter.replace(ORG, STAFF, List.of(), null))
                .isInstanceOf(AssignmentsUnavailableException.class);
        assertThatThrownBy(() -> adapter.revokeActive(ORG, STAFF)).isInstanceOf(AssignmentsUnavailableException.class);

        ticketing.respondWith(call -> null);
        assertThatThrownBy(() -> adapter.eventsOf(ORG, STAFF)).isInstanceOf(AssignmentsUnavailableException.class);
    }
}
