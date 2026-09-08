// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.http;

import com.nexaticket.ticketing.domain.port.OrderingPort;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang ordering-service. Không kiểu nào của Ordering đi quá lớp này. */
@Component
public class OrderingHttpAdapter implements OrderingPort {

    private final RestClient client;

    public OrderingHttpAdapter(@Value("${nexaticket.ticketing.ordering-url:http://localhost:8093}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Rộng hơn hạn 2 giây của saga checkout: ở đây không có khách nào đang chờ màn hình,
        // và để message giao lại chỉ vì chậm một giây là lãng phí.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client =
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public PaidOrder fetch(UUID orderId) {
        try {
            OrderResponse response = client.get()
                    .uri("/internal/orders/{id}", orderId)
                    .retrieve()
                    .body(OrderResponse.class);
            if (response == null) {
                throw new OrderingUnavailableException("Ordering trả body rỗng cho đơn " + orderId, null);
            }
            return response.toDomain();
        } catch (OrderingUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OrderingUnavailableException("Không đọc được đơn " + orderId, e);
        }
    }

    /** Hình dạng JSON của Ordering — chỉ tồn tại trong lớp này. */
    record OrderResponse(
            UUID id, UUID eventSessionId, UUID organizationId, UUID userId, String status, List<ItemResponse> items) {

        record ItemResponse(
                UUID orderItemId,
                UUID sessionSeatId,
                String seatCode,
                String zoneCode,
                String admissionType,
                String seatLabel,
                String ticketTypeName) {}

        PaidOrder toDomain() {
            return new PaidOrder(
                    id,
                    eventSessionId,
                    organizationId,
                    userId,
                    status,
                    items == null
                            ? List.of()
                            : items.stream()
                                    .map(item -> new Line(
                                            item.orderItemId(),
                                            item.sessionSeatId(),
                                            item.seatCode(),
                                            item.zoneCode(),
                                            item.admissionType(),
                                            item.seatLabel(),
                                            item.ticketTypeName()))
                                    .toList());
        }
    }
}
