// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.ticketing.application.OfflineCheckin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soát vé offline (gói dữ liệu + đồng bộ lô) và tìm khách quên vé, cho app NexaTicket Staff.
 *
 * <p>Cùng gốc đường dẫn với lượt quét ({@code .../sessions/{id}/checkins}) để api-gateway chỉ cần
 * một mẫu {@code /checkins/**}.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/sessions/{eventSessionId}/checkins")
public class OfflineCheckinController {

    private final OfflineCheckin offline;

    public OfflineCheckinController(OfflineCheckin offline) {
        this.offline = offline;
    }

    public record OfflineScanRequest(
            @NotNull UUID clientScanId,
            String qrToken,
            @Size(max = 36) String ticketCode,
            UUID ticketId,
            @Pattern(regexp = "IN|OUT") String direction,
            @NotNull Instant scannedAt,
            @Size(max = 80) String deviceId,
            @Size(max = 40) String deviceResult) {}

    public record SyncRequest(@NotNull @Size(max = OfflineCheckin.MAX_BATCH) List<@Valid OfflineScanRequest> scans) {}

    /**
     * @param identityChecked nhân viên đã đối chiếu giấy tờ tuỳ thân của khách — bắt buộc {@code true}
     */
    public record LookupAdmitRequest(
            @NotNull UUID ticketId, boolean identityChecked, @Size(max = 80) String deviceId) {}

    /**
     * Gói dữ liệu soát vé của suất: khoá công khai và vé. {@code since} (ISO-8601) để tải bù phần đổi
     * sau lần tải trước — dùng đúng {@code serverTime} mà lần trước trả về.
     */
    @GetMapping("/bundle")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public OfflineCheckin.Bundle bundle(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return offline.bundle(organizationId, eventSessionId, since);
    }

    /** Gửi lô lượt quét offline. Gửi lại cùng {@code clientScanId} không ghi lần hai. */
    @PostMapping("/sync")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public OfflineCheckin.SyncResult sync(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @Valid @RequestBody SyncRequest request) {
        return offline.sync(
                organizationId,
                eventSessionId,
                request.scans().stream()
                        .map(scan -> new OfflineCheckin.OfflineScan(
                                scan.clientScanId(),
                                scan.qrToken(),
                                scan.ticketCode(),
                                scan.ticketId(),
                                scan.direction(),
                                scan.scannedAt(),
                                scan.deviceId(),
                                scan.deviceResult()))
                        .toList());
    }

    /** Tìm khách trong suất theo tên (không dấu), email, SĐT hoặc mã vé — tối đa 20 dòng, đã che. */
    @GetMapping("/lookup")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public List<OfflineCheckin.TicketRow> search(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @RequestParam(name = "q", required = false) String query) {
        return offline.search(organizationId, eventSessionId, query);
    }

    /** Cho vào bằng tra cứu sau khi đối chiếu giấy tờ. Trả 200 kèm kết quả như một lượt quét. */
    @PostMapping("/lookup")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public CheckinController.ScanResponse admit(
            @PathVariable UUID organizationId,
            @PathVariable UUID eventSessionId,
            @Valid @RequestBody LookupAdmitRequest request) {
        return CheckinController.response(
                offline.admitByLookup(
                        organizationId,
                        eventSessionId,
                        request.ticketId(),
                        request.identityChecked(),
                        request.deviceId()),
                false);
    }

    /** Lượt quét offline bị xung đột của suất này. */
    @GetMapping("/conflicts")
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public List<OfflineCheckin.ConflictRow> conflicts(
            @PathVariable UUID organizationId, @PathVariable UUID eventSessionId) {
        return offline.conflicts(organizationId, eventSessionId);
    }
}
