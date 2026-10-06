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

    /**
     * Hình dạng JSON là <b>hợp đồng với ordering-service</b>, viết ra tường minh ở đây chứ không
     * trả thẳng record của tầng application.
     *
     * <p>Từng có lúc bên này trả {@code seatIds} còn bên kia đọc {@code seats} kèm giá. Cả hai đều
     * biên dịch được, cả hai bộ test đều xanh — vì integration test của Ordering thay Inventory
     * bằng hàng giả thay vì gọi HTTP thật — và checkout hỏng ở runtime với một
     * NullPointerException không gợi ý gì về nguyên nhân. Tên field ở đây phải khớp
     * {@code InventoryHttpAdapter.ReserveResponse}.
     */
    public record ReserveResponse(
            UUID orderId, UUID eventSessionId, UUID eventId, UUID organizationId, List<SeatDto> seats) {

        record SeatDto(
                UUID sessionSeatId,
                String seatCode,
                String zoneCode,
                String admissionType,
                String seatLabel,
                UUID ticketTypeId,
                String ticketTypeName,
                long priceVnd) {

            static SeatDto from(ReserveSeatsHandler.ReservedSeat row) {
                return new SeatDto(
                        row.sessionSeatId(),
                        row.seatCode(),
                        row.zoneCode(),
                        row.admissionType(),
                        row.seatLabel(),
                        row.ticketTypeId(),
                        row.ticketTypeName(),
                        row.priceVnd());
            }
        }
    }

    @PostMapping
    public ReserveResponse reserve(@RequestBody ReserveRequest request) {
        var result = reserveSeats.handle(
                new ReserveSeatsHandler.Command(request.orderId(), request.holdId(), request.userId()));

        return new ReserveResponse(
                result.orderId(),
                result.eventSessionId(),
                result.eventId(),
                result.organizationId(),
                result.seats().stream().map(ReserveResponse.SeatDto::from).toList());
    }

    /**
     * Bù trừ khi saga hỏng: chỗ về AVAILABLE, hạn mức của khách được trả lại.
     *
     * <p>Không có đặt chỗ nào cũng trả 204. Saga ghi cờ "đã đặt chỗ" TRƯỚC khi gọi, nên nó bù trừ
     * được một việc chưa từng xảy ra — trả 404 ở đó sẽ đẩy saga vào {@code COMPENSATION_PENDING}
     * vĩnh viễn vì một việc vốn không cần làm.
     */
    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID orderId) {
        settleReservation.cancel(orderId);
    }

    /**
     * RESERVED → SOLD sau khi thanh toán được xác nhận.
     *
     * <p>Đường thường là consumer {@code order.paid} bên trong service này; endpoint giữ lại cho
     * vận hành chốt tay một đơn khi message thất lạc. Idempotent, nên gọi thừa là vô hại.
     */
    @PostMapping("/{orderId}/settle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void settle(@PathVariable UUID orderId) {
        settleReservation.settle(orderId);
    }
}
