// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.http;

import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang inventory-service.
 *
 * <p>Không có kiểu dữ liệu nào của Inventory đi quá lớp này. Nếu Inventory đổi tên field trong
 * response, chỗ duy nhất phải sửa là đây — saga không biết gì về hình dạng JSON của nó.
 *
 * <p>Phân biệt hai loại hỏng là điểm quan trọng nhất của lớp này:
 *
 * <ul>
 *   <li><b>4xx</b> ⇒ câu trả lời dứt khoát của nghiệp vụ. Inventory chưa đổi gì, không cần bù trừ.
 *   <li><b>5xx / timeout / mạng hỏng</b> ⇒ <b>không biết</b> bên kia đã đặt chỗ hay chưa. Phải
 *       giả định là rồi và bù trừ, vì để sót một lần đặt chỗ nghĩa là ghế kẹt 15 phút.
 * </ul>
 */
@Component
public class InventoryHttpAdapter implements InventoryPort {

    private static final String SERVICE = "inventory-service";

    private final RestClient client;

    public InventoryHttpAdapter(@Qualifier("inventoryClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Reservation reserve(UUID orderId, UUID holdId, UUID userId) {
        try {
            ReserveResponse response = client.post()
                    .uri("/internal/reservations")
                    .body(Map.of("orderId", orderId, "holdId", holdId, "userId", userId))
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (status.is4xxClientError()) {
                            throw new SeatsUnavailableException(
                                    errorCodeOf(clientResponse), "Inventory refused the reservation");
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Inventory trả " + status, null);
                        }
                        return clientResponse.bodyTo(ReserveResponse.class);
                    });
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Inventory trả body rỗng", null);
            }
            return response.toDomain();
        } catch (SeatsUnavailableException | RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            // Timeout, connection refused, DNS hỏng — đều rơi vào đây.
            throw new RemoteCallException(SERVICE, "Không gọi được inventory-service", e);
        }
    }

    @Override
    public void cancelReservation(UUID orderId) {
        try {
            client.delete()
                    .uri("/internal/reservations/{orderId}", orderId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không huỷ được đặt chỗ của đơn " + orderId, e);
        }
    }

    /** Mã lỗi nghiệp vụ nằm trong body RFC 7807; đọc không ra thì coi như ghế đã bị lấy. */
    private static String errorCodeOf(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<?, ?> body = response.bodyTo(Map.class);
            Object code = body == null ? null : body.get("code");
            return code == null ? "SEAT_UNAVAILABLE" : code.toString();
        } catch (RuntimeException e) {
            return "SEAT_UNAVAILABLE";
        }
    }

    /** Hình dạng JSON của Inventory — chỉ tồn tại trong lớp này. */
    record ReserveResponse(UUID orderId, UUID eventSessionId, UUID organizationId, List<SeatDto> seats) {

        record SeatDto(
                UUID sessionSeatId,
                String seatCode,
                String zoneCode,
                String admissionType,
                String seatLabel,
                UUID ticketTypeId,
                String ticketTypeName,
                long priceVnd) {}

        Reservation toDomain() {
            return new Reservation(
                    eventSessionId,
                    organizationId,
                    seats.stream()
                            .map(s -> new Seat(
                                    s.sessionSeatId(),
                                    s.seatCode(),
                                    s.zoneCode(),
                                    s.admissionType(),
                                    s.seatLabel(),
                                    s.ticketTypeId(),
                                    s.ticketTypeName(),
                                    s.priceVnd()))
                            .toList());
        }
    }
}
