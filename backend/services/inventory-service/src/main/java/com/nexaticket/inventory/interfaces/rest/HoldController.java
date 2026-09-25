// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.command.ReleaseHoldHandler;
import com.nexaticket.platform.security.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Giữ chỗ.
 *
 * <p>{@code POST} bắt buộc có {@code Idempotency-Key} — filter của starter-idempotency chặn trước
 * khi request tới đây. Không có nó, khách bấm hai lần trên mạng chập chờn sẽ giữ hai lần và tự ăn
 * hết hạn mức của chính mình.
 */
@RestController
@RequestMapping("/v1")
public class HoldController {

    private final PlaceHoldHandler placeHold;
    private final ReleaseHoldHandler releaseHold;

    public HoldController(PlaceHoldHandler placeHold, ReleaseHoldHandler releaseHold) {
        this.placeHold = placeHold;
        this.releaseHold = releaseHold;
    }

    /**
     * @param seatIds các chỗ ngồi khách chỉ đích danh
     * @param seatedZones số lượng vé ngồi theo khu — hệ thống chọn chỗ hộ, gần sân khấu trước
     * @param standing số lượng vé đứng theo zone
     */
    public record PlaceHoldRequest(
            List<UUID> seatIds, List<@Valid ZoneLine> seatedZones, List<@Valid ZoneLine> standing) {

        /**
         * Một dòng "khu này, ngần này vé".
         *
         * <p>Dùng chung một kiểu cho cả hai danh sách, nhưng <b>hai danh sách</b> chứ không phải
         * một danh sách kèm cờ loại vé. Lý do nằm ở phía server: khu được cấp phát bằng câu lệnh
         * lọc {@code admission_type}, nên nếu khách khai sai loại thì kết quả là "khu không đủ
         * chỗ" — không có đường nào để một lời khai sai biến thành vé ngồi bán dưới giá vé đứng.
         * Gộp làm một danh sách thì server phải tự tra loại của từng khu, tức thêm một câu truy
         * vấn trên đúng đường nóng nhất của hệ thống.
         */
        public record ZoneLine(@NotBlank String zoneCode, @Positive int quantity) {}

        List<PlaceHoldHandler.Command.ZoneLine> seatedZoneLines() {
            return seatedZones == null
                    ? List.of()
                    : seatedZones.stream()
                            .map(line -> new PlaceHoldHandler.Command.ZoneLine(line.zoneCode(), line.quantity()))
                            .toList();
        }

        List<PlaceHoldHandler.Command.StandingLine> standingLines() {
            return standing == null
                    ? List.of()
                    : standing.stream()
                            .map(line -> new PlaceHoldHandler.Command.StandingLine(line.zoneCode(), line.quantity()))
                            .toList();
        }
    }

    public record HoldCreated(UUID holdId, Instant expiresAt, List<UUID> seatIds, long availabilityVersion) {}

    @PostMapping("/sessions/{eventSessionId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldCreated place(@PathVariable UUID eventSessionId, @Valid @RequestBody PlaceHoldRequest request) {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        var result = placeHold.handle(new PlaceHoldHandler.Command(
                eventSessionId,
                userId,
                request.seatIds() == null ? List.of() : request.seatIds(),
                request.seatedZoneLines(),
                request.standingLines()));
        return new HoldCreated(result.holdId(), result.expiresAt(), result.seatIds(), result.availabilityVersion());
    }

    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID holdId) {
        releaseHold.handle(holdId, TenantContext.requireAuthenticated().userId().value());
    }
}
