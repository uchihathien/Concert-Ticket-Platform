// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.http;

import com.nexaticket.payment.domain.port.OrderingPort;
import com.nexaticket.payment.infrastructure.sepay.SePayProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Báo ordering-service rằng tiền đã về. */
@Component
public class OrderingHttpAdapter implements OrderingPort {

    private final RestClient client;

    public OrderingHttpAdapter(SePayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Rộng hơn hạn 2 giây của saga checkout: ở đây không có ai đang nhìn màn hình chờ,
        // và trả lỗi cho SePay chỉ để họ retry là tốn hơn nhiều so với chờ thêm 3 giây.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl(properties.orderingUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public void confirmPayment(UUID orderId) {
        try {
            client.post()
                    .uri("/internal/orders/{orderId}/confirm-payment", orderId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // Ném lên để transaction rollback, kéo theo cả dòng dedupe — nhà cung cấp sẽ
            // retry và ta xử lý lại từ đầu. Nuốt lỗi ở đây sẽ để lại một đơn đã trả tiền
            // mà mãi không PAID, và không ai retry nữa vì ta đã trả 2xx.
            throw new OrderingUnavailableException("Không báo được ordering-service cho đơn " + orderId, e);
        }
    }
}
