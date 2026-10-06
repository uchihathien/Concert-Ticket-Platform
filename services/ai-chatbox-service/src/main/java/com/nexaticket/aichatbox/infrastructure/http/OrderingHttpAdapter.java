// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang ordering-service.
 *
 * <p>Gọi <b>đường của khách</b> ({@code GET /v1/orders/{id}}) kèm access token của chính khách,
 * <b>không</b> gọi {@code /internal/orders/{id}} bằng bí mật nội bộ. Lý do đầy đủ nằm ở
 * {@link OrderingClientPort}; tóm tắt: đường nội bộ không kiểm chủ sở hữu, nên dùng nó ở đây là
 * biến agent thành công cụ tra đơn hàng của người lạ, và nó chạy êm nên không ai phát hiện.
 *
 * <p>Không kiểu dữ liệu nào của Ordering đi quá lớp này. Ordering đổi tên field thì chỗ duy nhất
 * phải sửa là {@link OrderResponse}.
 */
@Component
public class OrderingHttpAdapter implements OrderingClientPort {

    private static final String SERVICE = "ordering-service";

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
}
