// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.ticketing.application.CheckinAssignments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phân công soát vé — cho identity-service gọi.
 *
 * <p>Nằm dưới {@code /internal/**}: không đi qua api-gateway, và người gọi là identity — nơi đã kiểm
 * người dùng có {@code ORG_MEMBERS_MANAGE} và người được gán đang là thành viên vai Staff, trước
 * khi gọi sang. Ở đây kiểm phần còn lại: sự kiện/suất có thuộc tổ chức không.
 */
@RestController
@RequestMapping("/internal/checkin-assignments")
public class InternalCheckinAssignmentController {

    private final CheckinAssignments assignments;

    public InternalCheckinAssignmentController(CheckinAssignments assignments) {
        this.assignments = assignments;
    }

    /**
     * Tên field là hợp đồng với {@code TicketingHttpAdapter} của identity-service.
     *
     * @param status {@code UPCOMING} · {@code ONGOING} · {@code EXPIRED} · {@code UNKNOWN}
     */
    public record AssignmentView(
            UUID userId,
            UUID eventId,
            UUID eventSessionId,
            String eventTitle,
            Instant startsAt,
            Instant expiresAt,
            String status) {

        static AssignmentView of(CheckinAssignments.Assigned a) {
            return new AssignmentView(
                    a.userId(),
                    a.eventId(),
                    a.eventSessionId(),
                    a.eventTitle(),
                    a.startsAt(),
                    a.expiresAt(),
                    a.status());
        }
    }

    /** @param eventSessionId bỏ trống là cả sự kiện */
    public record TargetView(@NotNull UUID eventId, UUID eventSessionId) {}

    /**
     * @param assignments các mục cần gán (theo sự kiện hoặc theo suất)
     * @param eventIds dạng cũ — chỉ theo sự kiện; được gộp với {@code assignments}
     */
    public record AssignRequest(
            @Valid @Size(max = 200) List<TargetView> assignments,
            @Size(max = 200) List<UUID> eventIds,
            UUID assignedBy) {

        /**
         * Thiếu cả hai danh sách là lỗi, KHÔNG phải "gỡ hết": một client gửi nhầm thân rỗng không
         * được xoá sạch phân công của ai. Muốn gỡ hết thì gửi mảng rỗng tường minh.
         */
        @AssertTrue(message = "Phải gửi assignments hoặc eventIds — mảng rỗng nếu muốn gỡ hết")
        public boolean isTargetsPresent() {
            return assignments != null || eventIds != null;
        }

        List<CheckinAssignments.Target> scopes() {
            List<CheckinAssignments.Target> scopes = new ArrayList<>();
            if (eventIds != null) {
                eventIds.forEach(eventId -> scopes.add(new CheckinAssignments.Target(eventId, null)));
            }
            if (assignments != null) {
                assignments.forEach(t -> scopes.add(new CheckinAssignments.Target(t.eventId(), t.eventSessionId())));
            }
            return scopes;
        }
    }

    /** Mọi phân công của một tổ chức (kể cả đã hết hạn), hoặc của một nhân viên khi có {@code userId}. */
    @GetMapping("/{organizationId}")
    public List<AssignmentView> list(@PathVariable UUID organizationId, @RequestParam(required = false) UUID userId) {
        return assignments.list(organizationId, userId).stream()
                .map(AssignmentView::of)
                .toList();
    }

    /** Đặt lại phân công CÒN HIỆU LỰC của một nhân viên. Rỗng là gỡ hết; phân công hết hạn được giữ. */
    @PutMapping("/{organizationId}/users/{userId}")
    public List<AssignmentView> assign(
            @PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody AssignRequest request) {
        return assignments.assign(organizationId, userId, request.scopes(), request.assignedBy()).stream()
                .map(AssignmentView::of)
                .toList();
    }

    /** Gỡ mọi phân công còn hiệu lực — người này bị gỡ khỏi tổ chức hoặc thôi làm Staff. */
    @DeleteMapping("/{organizationId}/users/{userId}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeActive(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        assignments.revokeActive(organizationId, userId);
    }
}
