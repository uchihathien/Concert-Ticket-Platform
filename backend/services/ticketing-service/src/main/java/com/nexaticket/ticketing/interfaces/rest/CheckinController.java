// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.CheckinAccess;
import com.nexaticket.ticketing.application.TicketingErrorCode;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soát vé — dùng bởi web-scanner và app mobile.
 *
 * <p>Luôn trả 200 kèm kết quả trong body, kể cả khi vé bị từ chối. Máy soát vé cần hiển thị
 * <i>lý do</i> ("đang ở trong từ 19:42", "vé của đêm khác") chứ không phải một mã lỗi HTTP — và ở
 * cửa vào, một màn hình đỏ không nói gì sẽ làm hàng người ùn lại.
 */
@RestController
@RequestMapping("/v1/sessions/{eventSessionId}/checkins")
public class CheckinController {

    private final CheckInHandler checkIn;
    private final CheckinAccess access;

    public CheckinController(CheckInHandler checkIn, CheckinAccess access) {
        this.checkIn = checkIn;
        this.access = access;
    }

    /**
     * @param qrToken mã QR vừa quét; bỏ trống khi gõ {@code ticketCode}
     * @param ticketCode mã vé gõ tay vì QR hỏng
     * @param direction {@code IN} hoặc {@code OUT}. Bỏ trống là máy soát vé đời đầu (chỉ biết quét
     *     Vào): kết quả khi đó được quy về sáu giá trị nó hiểu — xem {@link #legacy}
     * @param gate cổng đang soát, nếu sự kiện có nhiều cổng
     * @param override cho vào thủ công dù vé đang được ghi nhận là ở trong; cần {@code reason}
     */
    public record ScanRequest(
            String qrToken,
            String deviceId,
            @Size(max = 36) String ticketCode,
            @Pattern(regexp = "IN|OUT") String direction,
            @Size(max = 40) String gate,
            Boolean override,
            @Size(max = 300) String reason) {}

    /**
     * @param result ACCEPTED · EXITED · ALREADY_CHECKED_IN · NOT_INSIDE · REVOKED · WRONG_SESSION ·
     *     NOT_OPEN_YET · SESSION_ENDED · REENTRY_NOT_ALLOWED · INVALID_TOKEN · NOT_FOUND
     * @param ticketCode 8 ký tự đầu của id vé — đúng mã in trên vé của khách
     */
    public record ScanResponse(
            String result,
            String seatCode,
            String seatLabel,
            String ticketTypeName,
            String note,
            String direction,
            String ticketCode,
            String holderName,
            String zoneCode,
            int entryCount,
            Instant lastEntryAt,
            String lastEntryGate,
            UUID ticketSessionId) {}

    @PostMapping
    @RequiresPermission(Permission.CHECKIN_SCAN)
    public ScanResponse scan(@PathVariable UUID eventSessionId, @Valid @RequestBody ScanRequest request) {
        var tenant = TenantContext.requireActiveTenant();
        // Tổ chức lấy từ token đăng nhập của nhân viên, KHÔNG từ request: nếu để client gửi,
        // ai cũng soát được vé của tổ chức khác bằng cách đổi một trường JSON.
        return scan(checkIn, access, tenant, eventSessionId, request);
    }

    static ScanResponse scan(
            CheckInHandler checkIn, CheckinAccess access, TenantId tenant, UUID eventSessionId, ScanRequest request) {
        // Quyền soát vé VÀ (quản lý được sự kiện của tổ chức HOẶC được gán sự kiện này).
        var scope = access.requireScanner(tenant, eventSessionId);
        boolean override = Boolean.TRUE.equals(request.override());
        if (override) {
            // Ghi đè kết quả của máy là quyết định của người quản lý sự kiện, không phải của mọi
            // nhân viên thời vụ ở cửa.
            TenantContext.requirePermission(Permission.CATALOG_MANAGE, tenant);
            if (request.reason() == null || request.reason().isBlank()) {
                throw new ApiException(
                        TicketingErrorCode.OVERRIDE_REASON_REQUIRED, "Cho vào thủ công cần ghi rõ lý do");
            }
        }

        var result = checkIn.handle(new CheckInHandler.Command(
                request.qrToken(),
                request.ticketCode(),
                eventSessionId,
                scope.userId().value(),
                tenant.value(),
                request.deviceId(),
                request.direction(),
                request.gate(),
                override,
                request.reason()));

        return new ScanResponse(
                request.direction() == null ? legacy(result.result()) : result.result(),
                result.seatCode(),
                result.seatLabel(),
                result.ticketTypeName(),
                result.note(),
                result.direction(),
                result.ticketId() == null
                        ? null
                        : result.ticketId().toString().substring(0, 8).toUpperCase(),
                result.holderName(),
                result.zoneCode(),
                result.entryCount(),
                result.lastEntryAt(),
                result.lastEntryGate(),
                result.ticketSessionId());
    }

    /**
     * Quy kết quả về sáu giá trị mà máy soát vé đời đầu rẽ nhánh giao diện theo đó.
     *
     * <p>Một chuỗi nó không biết sẽ hiện ra thành màn hình trống giữa cửa vào. {@code note} vẫn
     * mang lý do thật, nên nhân viên vẫn đọc được "chưa tới giờ mở cổng".
     */
    private static String legacy(String result) {
        return switch (result) {
            case "NOT_OPEN_YET", "SESSION_ENDED" -> "WRONG_SESSION";
            case "REENTRY_NOT_ALLOWED" -> "ALREADY_CHECKED_IN";
            default -> result;
        };
    }
}
