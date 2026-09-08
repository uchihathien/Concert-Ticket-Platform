// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soát vé — dùng bởi app web-scanner.
 *
 * <p>Luôn trả 200 kèm kết quả trong body, kể cả khi vé bị từ chối. Máy soát vé cần hiển thị
 * <i>lý do</i> ("đã soát lúc 19:42", "vé của đêm khác") chứ không phải một mã lỗi HTTP — và ở cửa
 * vào, một màn hình đỏ không nói gì sẽ làm hàng người ùn lại.
 */
@RestController
@RequestMapping("/v1/sessions/{eventSessionId}/checkins")
public class CheckinController {

    private final CheckInHandler checkIn;

    public CheckinController(CheckInHandler checkIn) {
        this.checkIn = checkIn;
    }

    public record ScanRequest(@NotBlank String qrToken, String deviceId) {}

    /**
     * @param result ACCEPTED · ALREADY_CHECKED_IN · REVOKED · WRONG_SESSION · INVALID_TOKEN · NOT_FOUND
     */
    public record ScanResponse(String result, String seatCode, String seatLabel, String ticketTypeName, String note) {}

    @PostMapping
    public ScanResponse scan(@PathVariable UUID eventSessionId, @Valid @RequestBody ScanRequest request) {
        var scope = TenantContext.requireAuthenticated();
        // Tổ chức lấy từ token đăng nhập của nhân viên, KHÔNG từ request: nếu để client gửi,
        // ai cũng soát được vé của tổ chức khác bằng cách đổi một trường JSON.
        var result = checkIn.handle(new CheckInHandler.Command(
                request.qrToken(),
                eventSessionId,
                scope.userId().value(),
                TenantContext.requireActiveTenant().value(),
                request.deviceId()));

        return new ScanResponse(
                result.result(), result.seatCode(), result.seatLabel(), result.ticketTypeName(), result.note());
    }
}
