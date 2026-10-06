// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.http;

import com.nexaticket.payment.domain.port.OrderingPort;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang ordering-service.
 *
 * <p>Timeout ở đây dài hơn hạn 2 giây của saga checkout, và đó là chủ đích: không có người dùng nào
 * đang nhìn màn hình chờ, chỉ có một webhook mà nhà cung cấp sẵn sàng giao lại. Bỏ cuộc sớm chỉ đổi
 * một lần chờ lâu lấy trọn một vòng retry, mà vòng retry thì chậm hơn nhiều.
 */
@Component
public class OrderingHttpAdapter implements OrderingPort {

    private final RestClient client;

    public OrderingHttpAdapter(
            RestClient.Builder builder,
            @Value("${nexaticket.payment.ordering-url:http://localhost:8093}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        // Builder ĐƯỢC TIÊM, không phải RestClient.builder() tĩnh: chỉ bản này mang theo
        // correlation id và trace span sang service được gọi. Xem CorrelationPropagation.
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Confirmation confirmPayment(UUID orderId) {
        ConfirmResponse response;
        try {
            response = client.post()
                    .uri("/internal/orders/{orderId}/confirm-payment", orderId)
                    .retrieve()
                    .body(ConfirmResponse.class);
        } catch (RuntimeException e) {
            throw new OrderingUnavailableException("Không xác nhận được thanh toán cho đơn " + orderId, e);
        }

        // Ordering trả 2xx nghĩa là nó đã quyết định xong. Body lạ hoặc rỗng thì coi như PAID —
        // đó là hành vi của phiên bản Ordering cũ (204 không body), và đọc nó thành một lỗi sẽ
        // biến một lần rolling deploy thành một đợt webhook bị giao lại vô tận.
        Confirmation outcome = response == null ? null : response.parse();
        return outcome == null ? Confirmation.PAID : outcome;
    }

    /** Phải khớp {@code InternalOrderController.ConfirmPaymentResponse} của ordering-service. */
    private record ConfirmResponse(String outcome) {

        Confirmation parse() {
            if (outcome == null || outcome.isBlank()) {
                return null;
            }
            try {
                return Confirmation.valueOf(outcome);
            } catch (IllegalArgumentException e) {
                // Giá trị mới mà bản payment-service này chưa biết. Không đoán: coi như đã xử lý
                // xong theo đường thường, và dòng nhật ký webhook vẫn giữ nguyên payload.
                return null;
            }
        }
    }
}
