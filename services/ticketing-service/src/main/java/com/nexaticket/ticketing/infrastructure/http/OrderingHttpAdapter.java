// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.http;

import com.nexaticket.ticketing.domain.port.OrderingPort;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang ordering-service. Không kiểu nào của Ordering đi quá lớp này. */
@Component
public class OrderingHttpAdapter implements OrderingPort {

    private static final Logger log = LoggerFactory.getLogger(OrderingHttpAdapter.class);

    private final RestClient client;

    public OrderingHttpAdapter(
            RestClient.Builder builder,
            @Value("${nexaticket.ticketing.ordering-url:http://localhost:8093}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Rộng hơn hạn 2 giây của saga checkout: ở đây không có khách nào đang chờ màn hình,
        // và để message giao lại chỉ vì chậm một giây là lãng phí.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        // Builder ĐƯỢC TIÊM, không phải RestClient.builder() tĩnh: chỉ bản này mang theo
        // correlation id và trace span sang service được gọi. Xem CorrelationPropagation.
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Map<UUID, String> statusesOf(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        try {
            StatusesResponse response = client.post()
                    .uri("/internal/orders/statuses")
                    .body(new StatusesRequest(List.copyOf(orderIds)))
                    .retrieve()
                    .body(StatusesResponse.class);

            return response == null || response.statuses() == null ? Map.of() : response.statuses();
        } catch (RuntimeException e) {
            // Nuốt có chủ đích — xem OrderingPort.statusesOf. Vẫn log để một Ordering sập lâu ngày
            // không đi qua trong im lặng.
            log.warn("Không đọc được trạng thái {} đơn: {}", orderIds.size(), e.toString());
            return Map.of();
        }
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
        } catch (HttpClientErrorException.NotFound e) {
            // 404 là câu trả lời DỨT KHOÁT, không phải sự cố tạm thời. Gói nó chung với lỗi mạng
            // sẽ khiến consumer giao lại mãi một message không bao giờ xử lý được, và chặn cả
            // hàng đợi phía sau.
            throw new OrderNotFoundException("Ordering nói không có đơn " + orderId, e);
        } catch (HttpClientErrorException e) {
            // Mọi 4xx khác cũng vậy: request của ta sai, hỏi lại y hệt thì vẫn sai. Riêng 429 là
            // ngoại lệ — đó là "chậm lại", tức là thử lại được.
            if (e.getStatusCode().value() == 429) {
                throw new OrderingUnavailableException("Ordering đang giới hạn tần suất, đơn " + orderId, e);
            }
            throw new OrderNotFoundException(
                    "Ordering từ chối yêu cầu cho đơn " + orderId + ": " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            // Còn lại là 5xx, timeout, mạng đứt — thử lại có nghĩa.
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

    /** Tên field là hợp đồng với {@code InternalOrderController.StatusesRequest} của ordering. */
    private record StatusesRequest(List<UUID> orderIds) {}

    private record StatusesResponse(Map<UUID, String> statuses) {}
}
