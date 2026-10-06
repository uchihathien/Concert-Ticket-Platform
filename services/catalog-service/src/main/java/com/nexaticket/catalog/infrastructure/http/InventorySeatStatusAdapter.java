// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.http;

import com.nexaticket.catalog.domain.port.SeatStatusPort;
import com.nexaticket.catalog.domain.port.UpstreamUnavailableException;
import com.nexaticket.platform.security.InternalApiProperties;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang inventory-service.
 *
 * <p>Không kiểu nào của Inventory đi quá lớp này. Đổi tên field bên kia thì chỗ duy nhất phải sửa
 * là ở đây, và bảng điều khiển không biết gì về hình dạng JSON của nó.
 *
 * <p>Phân biệt hai loại hỏng, giống {@code InventoryHttpAdapter} của ordering nhưng với kết luận
 * ngược lại vì đây là đường đọc:
 *
 * <ul>
 *   <li><b>404</b> ⇒ suất chưa được dựng tồn kho. Đó là câu trả lời <b>bình thường</b> cho một sự
 *       kiện còn nháp, không phải sự cố — trả rỗng.
 *   <li><b>5xx / timeout / mạng hỏng</b> ⇒ không hỏi được. Ném lên để màn hình nói "chưa có số"
 *       thay vì nói "0 chỗ đã bán".
 * </ul>
 */
@Component
public class InventorySeatStatusAdapter implements SeatStatusPort {

    private static final String SERVICE = "inventory-service";

    private final RestClient client;
    private final String internalToken;

    public InventorySeatStatusAdapter(@Qualifier("inventoryClient") RestClient client, InternalApiProperties internal) {
        this.client = client;
        this.internalToken = internal.sharedSecret();
    }

    @Override
    public Optional<SessionSeatStatus> forSession(UUID eventSessionId) {
        try {
            SeatStatusResponse response = client.get()
                    .uri("/internal/sessions/{id}/seat-status", eventSessionId)
                    .headers(headers -> {
                        if (internalToken != null && !internalToken.isBlank()) {
                            headers.set(InternalApiFilter.HEADER, internalToken);
                        }
                    })
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (status.value() == 404) {
                            return null;
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new UpstreamUnavailableException(SERVICE, "Inventory trả " + status, null);
                        }
                        return clientResponse.bodyTo(SeatStatusResponse.class);
                    });
            return Optional.ofNullable(response).map(SeatStatusResponse::toDomain);
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Timeout, connection refused, DNS hỏng — đều rơi vào đây.
            throw new UpstreamUnavailableException(SERVICE, "Không gọi được inventory-service", e);
        }
    }

    /**
     * Hình dạng JSON là hợp đồng với {@code InternalSeatStatusController} của inventory-service.
     *
     * <p>Viết ra tường minh chứ không dùng {@code Map}: đổi tên field bên kia thì ở đây thành
     * {@code null} lặng lẽ, và màn hình hiện 0 chỗ cho một suất đã bán hết.
     */
    record SeatStatusResponse(UUID eventSessionId, long availabilityVersion, List<ZoneRow> zones) {

        record ZoneRow(
                String zoneCode, String admissionType, int available, int held, int reserved, int sold, int blocked) {}

        SessionSeatStatus toDomain() {
            return new SessionSeatStatus(
                    eventSessionId,
                    availabilityVersion,
                    zones == null
                            ? List.of()
                            : zones.stream()
                                    .map(z -> new ZoneSeatStatus(
                                            z.zoneCode(),
                                            z.admissionType(),
                                            z.available(),
                                            z.held(),
                                            z.reserved(),
                                            z.sold(),
                                            z.blocked()))
                                    .toList());
        }
    }
}
