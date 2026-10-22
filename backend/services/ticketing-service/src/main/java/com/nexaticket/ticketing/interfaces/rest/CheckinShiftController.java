// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.ticketing.application.CheckinShifts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
 * Ca trực soát vé.
 *
 * <p>Nửa đầu cho ban tổ chức ({@code CATALOG_MANAGE}): cổng của sự kiện, ca theo suất, ai đang trực.
 * Nửa sau cho nhân viên ({@code CHECKIN_SCAN}): ca của tôi, bắt đầu ca, kết thúc ca kèm tổng kết.
 *
 * <p>Đường dẫn {@code /checkin/...} chứ không {@code /events/{id}/gates}: {@code /organizations/*}
 * {@code /events/**} là của catalog-service ở api-gateway.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/checkin")
public class CheckinShiftController {

    private final CheckinShifts shifts;

    public CheckinShiftController(CheckinShifts shifts) {
        this.shifts = shifts;
    }

    public record GateRequest(@NotBlank @Size(max = 40) String name) {}

    public record ShiftRequest(
            @NotNull UUID eventSessionId,
            @NotNull UUID gateId,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull @Size(max = 100) List<UUID> staffIds) {

        CheckinShifts.ShiftDraft draft() {
            return new CheckinShifts.ShiftDraft(eventSessionId, gateId, startsAt, endsAt, staffIds);
        }
    }

    public record StartRequest(@Size(max = 80) String deviceId) {}

    // --- ban tổ chức ---

    @GetMapping("/events/{eventId}/gates")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    public List<CheckinShifts.GateView> gates(@PathVariable UUID organizationId, @PathVariable UUID eventId) {
        return shifts.gates(organizationId, eventId);
    }

    @PostMapping("/events/{eventId}/gates")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CheckinShifts.GateView createGate(
            @PathVariable UUID organizationId, @PathVariable UUID eventId, @Valid @RequestBody GateRequest request) {
        return shifts.createGate(organizationId, eventId, request.name());
    }

    @PutMapping("/gates/{gateId}")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void renameGate(
            @PathVariable UUID organizationId, @PathVariable UUID gateId, @Valid @RequestBody GateRequest request) {
        shifts.renameGate(organizationId, gateId, request.name());
    }

    @DeleteMapping("/gates/{gateId}")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGate(@PathVariable UUID organizationId, @PathVariable UUID gateId) {
        shifts.deleteGate(organizationId, gateId);
    }

    /** Ca của một sự kiện, kèm trạng thái theo giờ và ai đang trực (đã bấm bắt đầu, chưa kết thúc). */
    @GetMapping("/events/{eventId}/shifts")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    public List<CheckinShifts.ShiftView> shiftsOfEvent(@PathVariable UUID organizationId, @PathVariable UUID eventId) {
        return shifts.shiftsOfEvent(organizationId, eventId);
    }

    @PostMapping("/shifts")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CheckinShifts.ShiftView createShift(
            @PathVariable UUID organizationId, @Valid @RequestBody ShiftRequest request) {
        return shifts.createShift(organizationId, request.draft());
    }

    @PutMapping("/shifts/{shiftId}")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    public CheckinShifts.ShiftView updateShift(
            @PathVariable UUID organizationId, @PathVariable UUID shiftId, @Valid @RequestBody ShiftRequest request) {
        return shifts.updateShift(organizationId, shiftId, request.draft());
    }

    @DeleteMapping("/shifts/{shiftId}")
    @RequiresPermission(Permission.CATALOG_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteShift(@PathVariable UUID organizationId, @PathVariable UUID shiftId) {
        shifts.deleteShift(organizationId, shiftId);
    }

    // --- nhân viên ---

    /** Ca hôm nay và sắp tới của chính người gọi. */
    @GetMapping("/my-shifts")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public List<CheckinShifts.MyShift> myShifts(@PathVariable UUID organizationId) {
        return shifts.myShifts(organizationId);
    }

    @PostMapping("/shifts/{shiftId}/start")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public CheckinShifts.MyShift start(
            @PathVariable UUID organizationId,
            @PathVariable UUID shiftId,
            @Valid @RequestBody(required = false) StartRequest request) {
        return shifts.startShift(organizationId, shiftId, request == null ? null : request.deviceId());
    }

    /** Kết thúc ca và trả tổng kết để bàn giao: vào, ra, từ chối, cho vào thủ công, offline. */
    @PostMapping("/shifts/{shiftId}/end")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public CheckinShifts.Handover end(@PathVariable UUID organizationId, @PathVariable UUID shiftId) {
        return shifts.endShift(organizationId, shiftId);
    }
}
