// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang ordering-service.
 *
 * <p>Gọi <b>đường của khách</b> ({@code /v1/orders/**}, {@code /v1/me/orders}) kèm access token của
 * chính khách, <b>không</b> gọi {@code /internal/orders/{id}} bằng bí mật nội bộ. Lý do đầy đủ nằm
 * ở {@link OrderingClientPort}; tóm tắt: đường nội bộ không kiểm chủ sở hữu, nên dùng nó ở đây là
 * biến agent thành công cụ tra đơn hàng của người lạ, và nó chạy êm nên không ai phát hiện.
 *
 * <p>Không kiểu dữ liệu nào của Ordering đi quá lớp này. Ordering đổi tên field thì chỗ duy nhất
 * phải sửa là {@link OrderResponse} và {@link OrderCreatedResponse}.
 */
@Component
public class OrderingHttpAdapter implements OrderingClientPort {

    private static final String SERVICE = "ordering-service";

    /**
     * Mã lỗi nghiệp vụ của ordering thành câu cho khách.
     *
     * <p>Những mã KHÔNG có ở đây mà vẫn là 4xx thì rơi vào câu chung — vẫn là "từ chối", không phải
     * "sự cố". Mô hình cần phân biệt được hai thứ đó hơn là cần câu thật chi tiết.
     */
    private static final Map<String, String> REJECTIONS = Map.of(
            "HOLD_EXPIRED", "Chỗ giữ đã hết hạn 10 phút trước khi kịp đặt đơn. Mời khách chọn lại.",
            "SEAT_UNAVAILABLE", "Chỗ vừa giữ đã không còn — có người khác lấy trước. Mời khách chọn lại.",
            "PROMOTION_INVALID", "Mã khuyến mãi không hợp lệ.",
            "ORDER_NOT_OPEN", "Đơn này không còn ở trạng thái chờ thanh toán.");

    private final RestClient client;

    public OrderingHttpAdapter(@Qualifier("orderingClient") RestClient client) {
        this.client = client;
    }

    @Override
    public OrderSummary fetchOrder(UUID orderId, String callerAccessToken) {
        try {
            OrderResponse response = client.get()
                    .uri("/v1/orders/{orderId}", orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerAccessToken)
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        // 404 (không có đơn) và 403 (đơn của người khác) đều là "không có đơn nào
                        // như vậy cho bạn". Phân biệt hai cái ở đây thì agent nói ra sự khác biệt,
                        // và khách dò được mã đơn nào có thật.
                        if (status.value() == 404 || status.value() == 403) {
                            throw new OrderNotFoundException(orderId);
                        }
                        // 401 nghĩa là token của khách đã hết hạn giữa chừng — với agent thì đó là
                        // sự cố hạ tầng, vì nó không có cách nào làm mới token hộ khách.
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Ordering trả " + status, null);
                        }
                        return clientResponse.bodyTo(OrderResponse.class);
                    });
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Ordering trả body rỗng", null);
            }
            return response.toDomain();
        } catch (OrderNotFoundException | RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            // Quá hạn, connection refused, DNS hỏng.
            throw new RemoteCallException(SERVICE, "Không gọi được ordering-service", e);
        }
    }

    @Override
    public List<OrderSummary> listMyOrders(String callerAccessToken, int limit) {
        try {
            List<OrderResponse> response = client.get()
                    .uri(uri -> uri.path("/v1/me/orders")
                            .queryParam("limit", Math.max(1, limit))
                            .queryParam("offset", 0)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerAccessToken)
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Ordering trả " + status, null);
                        }
                        return clientResponse.bodyTo(new ParameterizedTypeReference<List<OrderResponse>>() {});
                    });
            return response == null
                    ? List.of()
                    : response.stream().map(OrderResponse::toDomain).toList();
        } catch (RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được ordering-service", e);
        }
    }

    @Override
    public PlacedOrder placeOrder(UUID holdId, String callerAccessToken, String idempotencyKey) {
        try {
            OrderCreatedResponse response = client.post()
                    .uri("/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerAccessToken)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("holdId", holdId))
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (status.is4xxClientError()) {
                            throw new BookingRejectedException(
                                    ApiErrors.reason(clientResponse, REJECTIONS, "Ordering không nhận đơn này."));
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Ordering trả " + status, null);
                        }
                        return clientResponse.bodyTo(OrderCreatedResponse.class);
                    });
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Ordering trả body rỗng", null);
            }
            return response.toDomain();
        } catch (BookingRejectedException | RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được ordering-service", e);
        }
    }

    /**
     * Chỉ những trường agent cần.
     *
     * <p>Jackson bỏ qua field lạ theo mặc định của Boot, nên bản này cố tình <b>không</b> khai
     * {@code vietQrPayload}, {@code checkoutUrl}, {@code paymentReference}: chúng không trả lời
     * được câu hỏi nào của khách, và thứ không đi vào tiến trình thì không thể lọt vào prompt.
     */
    private record OrderResponse(
            UUID id,
            String orderNumber,
            UUID eventId,
            UUID eventSessionId,
            String status,
            long totalVnd,
            Instant paymentExpiresAt,
            Instant paidAt,
            List<Item> items) {

        private record Item(String zoneCode, String seatLabel, String ticketTypeName, long unitPriceVnd) {}

        OrderSummary toDomain() {
            return new OrderSummary(
                    id,
                    orderNumber,
                    eventId,
                    eventSessionId,
                    status,
                    totalVnd,
                    paymentExpiresAt,
                    paidAt,
                    items == null
                            ? List.of()
                            : items.stream()
                                    .map(i -> new OrderSummary.Item(describe(i), i.unitPriceVnd()))
                                    .toList());
        }

        /** Vé ngồi có nhãn ghế, vé đứng chỉ có khu vực — ghép sao cho khách đọc là hiểu. */
        private static String describe(Item item) {
            String where = item.seatLabel() != null && !item.seatLabel().isBlank()
                    ? item.zoneCode() + " · ghế " + item.seatLabel()
                    : item.zoneCode() + " · vé đứng";
            return item.ticketTypeName() == null ? where : item.ticketTypeName() + " · " + where;
        }
    }

    /**
     * Hợp đồng với {@code OrderController.OrderCreated}. Chuỗi VietQR và mã tham chiếu ngân hàng
     * cố ý không khai — xem {@link PlacedOrder}.
     */
    private record OrderCreatedResponse(
            UUID orderId, String orderNumber, long totalVnd, String checkoutUrl, Instant paymentExpiresAt) {

        PlacedOrder toDomain() {
            return new PlacedOrder(orderId, orderNumber, totalVnd, checkoutUrl, paymentExpiresAt);
        }
    }
}
