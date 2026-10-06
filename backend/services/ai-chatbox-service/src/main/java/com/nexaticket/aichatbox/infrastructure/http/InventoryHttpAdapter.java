// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.model.TicketHold;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.InventoryClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang inventory-service, cho việc giữ chỗ.
 *
 * <p>Gọi {@code POST /v1/sessions/{id}/holds} — cùng endpoint nút "Đặt vé" trên web gọi — bằng
 * token của khách. Hợp đồng ở {@code HoldController.PlaceHoldRequest}: hai danh sách tách nhau
 * cho vé ngồi ({@code seatedZones}) và vé đứng ({@code standing}); agent điền đúng một trong hai
 * theo loại khu đã tra từ catalog.
 */
@Component
public class InventoryHttpAdapter implements InventoryClientPort {

    private static final Logger log = LoggerFactory.getLogger(InventoryHttpAdapter.class);

    private static final String SERVICE = "inventory-service";

    /** Mã lỗi nghiệp vụ của inventory thành câu cho khách — xem {@code InventoryErrorCode}. */
    private static final Map<String, String> REJECTIONS = Map.of(
            "ZONE_SOLD_OUT", "Khu này không còn đủ chỗ trống cho số vé khách muốn.",
            "SEAT_UNAVAILABLE", "Chỗ trong khu này vừa có người khác giữ. Mời khách thử khu khác hoặc ít vé hơn.",
            "SALES_CLOSED", "Suất này đã đóng bán vé.",
            "SESSION_NOT_FOUND", "Suất diễn không tồn tại — lấy lại sessionId từ getEventDetails.",
            "HOLD_LIMIT_EXCEEDED", "Số vé vượt hạn mức một lần giữ chỗ của sự kiện. Mời khách giảm số vé.",
            "CUSTOMER_LIMIT_EXCEEDED", "Khách đã đạt hạn mức mua của sự kiện này (tính cả đơn đang chờ thanh toán).");

    private final RestClient client;

    public InventoryHttpAdapter(@Qualifier("inventoryClient") RestClient client) {
        this.client = client;
    }

    @Override
    public TicketHold holdZone(
            UUID eventSessionId,
            String zoneCode,
            int quantity,
            ZoneAdmission admission,
            String callerAccessToken,
            String idempotencyKey) {

        List<Map<String, Object>> line = List.of(Map.of("zoneCode", zoneCode, "quantity", quantity));
        Map<String, Object> body = admission == ZoneAdmission.STANDING
                ? Map.of("seatIds", List.of(), "seatedZones", List.of(), "standing", line)
                : Map.of("seatIds", List.of(), "seatedZones", line, "standing", List.of());

        try {
            HoldCreatedResponse response = client.post()
                    .uri("/v1/sessions/{eventSessionId}/holds", eventSessionId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerAccessToken)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (status.is4xxClientError()) {
                            throw new BookingRejectedException(ApiErrors.reason(
                                    clientResponse, REJECTIONS, "Inventory không nhận yêu cầu giữ chỗ này."));
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Inventory trả " + status, null);
                        }
                        return clientResponse.bodyTo(HoldCreatedResponse.class);
                    });
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Inventory trả body rỗng", null);
            }
            return response.toDomain(quantity);
        } catch (BookingRejectedException | RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được inventory-service", e);
        }
    }

    /** Không ném — xem {@link InventoryClientPort#releaseHold}. */
    @Override
    public void releaseHold(UUID holdId, String callerAccessToken) {
        try {
            client.delete()
                    .uri("/v1/holds/{holdId}", holdId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerAccessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Không nhả được chỗ giữ {} — nó sẽ tự hết hạn: {}", holdId, e.getMessage());
        }
    }

    /**
     * Hợp đồng với {@code HoldController.HoldCreated}.
     *
     * @param seatIds chỗ hệ thống đã chọn hộ; chỉ dùng để đếm, agent không đọc mã ghế cho khách
     */
    private record HoldCreatedResponse(UUID holdId, Instant expiresAt, List<UUID> seatIds, long availabilityVersion) {

        TicketHold toDomain(int requested) {
            // Vé đứng không có seatIds — số giữ được bằng số xin.
            int held = seatIds == null || seatIds.isEmpty() ? requested : seatIds.size();
            return new TicketHold(holdId, expiresAt, held);
        }
    }
}
