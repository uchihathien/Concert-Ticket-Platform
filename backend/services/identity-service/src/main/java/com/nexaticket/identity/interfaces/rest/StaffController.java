// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.application.command.CheckinAssignmentHandler;
import com.nexaticket.identity.application.command.StaffInvitationHandler;
import com.nexaticket.identity.application.query.StaffQueries;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mục Phân công soát vé: ban tổ chức gán sự kiện (hoặc suất) cho thành viên có vai Staff.
 *
 * <p>Thêm, đổi vai và gỡ thành viên nằm ở {@code /organizations/{id}/members}. Ở đây chỉ còn những
 * việc riêng của nhân viên soát vé. Luồng tạo tài khoản nhân viên qua email đã ngừng:
 * {@code POST /staff} trả 410; gửi lại / huỷ lời mời cũ vẫn chạy cho những lời mời đã phát.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/staff")
@SuppressWarnings("removal") // Gửi lại / huỷ lời mời cũ còn dùng StaffInvitationHandler cho tới khi hết lời mời.
public class StaffController {

    private final StaffInvitationHandler invitations;
    private final CheckinAssignmentHandler assignments;
    private final StaffQueries queries;

    public StaffController(
            StaffInvitationHandler invitations, CheckinAssignmentHandler assignments, StaffQueries queries) {
        this.invitations = invitations;
        this.assignments = assignments;
        this.queries = queries;
    }

    public record InviteRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(max = 200) List<UUID> eventIds) {}

    public record InviteResponse(String outcome, String userId) {}

    /**
     * Gán theo cả sự kiện ({@code eventIds}) và/hoặc theo từng suất ({@code assignments} có
     * {@code eventSessionId}). Hai danh sách được gộp; cả hai rỗng là gỡ hết phân công còn hiệu lực.
     */
    public record EventsRequest(
            @Size(max = 200) List<UUID> eventIds, @Valid @Size(max = 200) List<TargetRequest> assignments) {

        /** Thiếu cả hai là lỗi, không phải "gỡ hết" — gửi mảng rỗng tường minh nếu muốn gỡ hết. */
        @AssertTrue(message = "Phải gửi eventIds hoặc assignments — mảng rỗng nếu muốn gỡ hết")
        public boolean isTargetsPresent() {
            return eventIds != null || assignments != null;
        }

        List<CheckinAssignmentHandler.Item> targets() {
            List<CheckinAssignmentHandler.Item> targets = new ArrayList<>();
            if (eventIds != null) {
                eventIds.forEach(eventId -> targets.add(new CheckinAssignmentHandler.Item(eventId, null)));
            }
            if (assignments != null) {
                assignments.forEach(
                        item -> targets.add(new CheckinAssignmentHandler.Item(item.eventId(), item.eventSessionId())));
            }
            return targets;
        }
    }

    /** @param eventSessionId bỏ trống là gán cả sự kiện */
    public record TargetRequest(@NotNull UUID eventId, UUID eventSessionId) {}

    @GetMapping
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<StaffQueries.StaffRow> list(@PathVariable UUID organizationId) {
        return queries.staffOf(TenantId.of(organizationId));
    }

    /**
     * ĐÃ NGỪNG — luôn trả 410.
     *
     * <p>Chọn 410 thay vì xoá hẳn đường này: client cũ (bản app/web chưa cập nhật) nhận một mã lỗi
     * nói rõ "đã bỏ" kèm chỉ dẫn, thay vì 404/405 khó hiểu. Không gọi {@code StaffInvitationHandler},
     * nên không tài khoản Keycloak nào được tạo nữa.
     *
     * @deprecated nhân viên tự đăng ký, rồi thêm ở {@code POST /organizations/{id}/members}
     */
    @Deprecated(since = "2026-11")
    @PostMapping
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public InviteResponse invite(@PathVariable UUID organizationId, @Valid @RequestBody InviteRequest request) {
        throw new ApiException(
                IdentityErrorCode.STAFF_INVITE_RETIRED,
                "Không tạo tài khoản nhân viên nữa. Nhờ nhân viên tự đăng ký NexaTicket, rồi thêm họ ở mục Thành viên với vai Staff.");
    }

    /** Sự kiện mà CHÍNH người gọi được soát — app soát vé dùng để chỉ hiện đúng những suất đó. */
    @GetMapping("/me/events")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public StaffQueries.MyEvents myEvents(@PathVariable UUID organizationId) {
        return queries.myEvents(TenantId.of(organizationId));
    }

    @PutMapping("/{userId}/events")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    public List<StaffQueries.StaffRow> setEvents(
            @PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody EventsRequest request) {
        assignments.assign(TenantId.of(organizationId), UserId.of(userId), request.targets());
        return queries.staffOf(TenantId.of(organizationId));
    }

    @PostMapping("/{userId}/invitation/resend")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resend(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        invitations.resend(TenantId.of(organizationId), UserId.of(userId));
    }

    @DeleteMapping("/{userId}/invitation")
    @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        invitations.cancel(TenantId.of(organizationId), UserId.of(userId));
    }
}
