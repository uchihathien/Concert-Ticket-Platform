// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import com.nexaticket.inventory.application.command.ReserveSeatsHandler;
import com.nexaticket.inventory.application.command.SettleReservationHandler;
import jakarta.validation.constraints.NotNull;
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
 * Open Host Service cho saga checkout — <b>chỉ service nội bộ gọi được</b>.
 *
 * <p>Gateway không route {@code /internal/**} ra ngoài, và security config của service từ chối
 * request tới đây nếu không mang token nội bộ. Đây là các thao tác đổi trạng thái tiền và vé, nên
 * để lọt ra internet là mất kiểm soát tồn kho.
 *
 * <p>Cả ba endpoint đều idempotent theo {@code orderId}: saga có thể chạy lại bất kỳ bước nào sau
 * timeout mạng, và bước bù trừ có thể chạy nhiều lần.
 */
@RestController
@RequestMapping("/internal/reservations")
public class InternalReservationController {

    private final ReserveSeatsHandler reserveSeats;
    private final SettleReservationHandler settleReservation;

    public InternalReservationController(ReserveSeatsHandler reserveSeats, SettleReservationHandler settleReservation) {
        this.reserveSeats = reserveSeats;
        this.settleReservation = settleReservation;
    }

    public record ReserveRequest(@NotNull UUID orderId, @NotNull UUID holdId, @NotNull UUID userId) {}

    public record ReserveResponse(UUID orderId, UUID eventSessionId, List<UUID> seatIds) {}

    @PostMapping
    public ReserveResponse reserve(@RequestBody ReserveRequest request) {
        var result = reserveSeats.handle(
                new ReserveSeatsHandler.Command(request.orderId(), request.holdId(), request.userId()));
        return new ReserveResponse(result.orderId(), result.eventSessionId(), result.seatIds());
    }

    /** Bù trừ khi saga hỏng: chỗ về AVAILABLE, hạn mức của khách được trả lại. */
    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID orderId) {
        settleReservation.cancel(orderId);
    }

    /** RESERVED → SOLD sau khi thanh toán được xác nhận. */
    @PostMapping("/{orderId}/settle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void settle(@PathVariable UUID orderId) {
        settleReservation.settle(orderId);
    }
}
