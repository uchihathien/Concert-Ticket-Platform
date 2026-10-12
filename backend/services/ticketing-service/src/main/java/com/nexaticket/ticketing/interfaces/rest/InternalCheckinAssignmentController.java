// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.ticketing.application.CheckinAssignments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Việc gán nhân viên soát vé vào sự kiện — cho identity-service gọi.
 *
 * <p>Nằm dưới {@code /internal/**}: không đi qua api-gateway, và người gọi là một service đã tự
 * kiểm quyền của người dùng ({@code ORG_MEMBERS_MANAGE}) trước khi gọi sang.
 */
@RestController
@RequestMapping("/internal/checkin-assignments")
public class InternalCheckinAssignmentController {

    private final CheckinAssignments assignments;

    public InternalCheckinAssignmentController(CheckinAssignments assignments) {
        this.assignments = assignments;
    }

    public record AssignmentView(UUID userId, UUID eventId) {}

    public record ReplaceRequest(@NotNull @Size(max = 200) List<UUID> eventIds, UUID assignedBy) {}

    /** Mọi việc gán của một tổ chức, hoặc của một nhân viên khi có {@code userId}. */
    @GetMapping("/{organizationId}")
    public List<AssignmentView> list(@PathVariable UUID organizationId, @RequestParam(required = false) UUID userId) {
        return assignments.list(organizationId, userId).stream()
                .map(a -> new AssignmentView(a.userId(), a.eventId()))
                .toList();
    }

    /** Đặt lại danh sách sự kiện của một nhân viên. Danh sách rỗng là gỡ khỏi mọi sự kiện. */
    @PutMapping("/{organizationId}/users/{userId}")
    public List<AssignmentView> replace(
            @PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody ReplaceRequest request) {
        return assignments.replace(organizationId, userId, request.eventIds(), request.assignedBy()).stream()
                .map(a -> new AssignmentView(a.userId(), a.eventId()))
                .toList();
    }
}
