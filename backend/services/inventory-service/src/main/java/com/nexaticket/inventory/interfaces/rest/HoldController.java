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
     * @param standing số lượng vé đứng theo zone
     */
    public record PlaceHoldRequest(List<UUID> seatIds, List<@Valid StandingLine> standing) {

        public record StandingLine(@NotBlank String zoneCode, @Positive int quantity) {}

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
                request.standingLines()));
        return new HoldCreated(result.holdId(), result.expiresAt(), result.seatIds(), result.availabilityVersion());
    }

    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID holdId) {
        releaseHold.handle(holdId, TenantContext.requireAuthenticated().userId().value());
    }
}
