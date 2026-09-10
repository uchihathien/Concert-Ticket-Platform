// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP cho lời gọi liên service.
 *
 * <p><b>Hạn ngắn hơn hạn của mô hình, và có lý do.</b> Một lời gọi tool nằm <i>bên trong</i> một
 * lượt chat: khách đã chờ mô hình suy nghĩ, giờ chờ thêm lần tra cứu, rồi chờ mô hình đọc kết quả.
 * Ba giây là ngân sách của cả chuỗi đó, không phải của riêng lời gọi này.
 *
 * <p><b>Không retry.</b> Retry một lời gọi đã quá hạn sẽ nhân đôi thời gian chờ trong khi mô hình
 * đang giữ chỗ trong hàng đợi. Hỏng thì nói với mô hình là hỏng — nó biết cách nói lại với khách.
 */
@Configuration
@ConfigurationProperties(prefix = "nexaticket.aichatbox.services")
public class InternalClients {

    private String orderingUrl = "http://localhost:8093";
    private Duration timeout = Duration.ofSeconds(3);

    @Bean
    public RestClient orderingClient(RestClient.Builder builder) {
        // Builder ĐƯỢC TIÊM, không phải RestClient.builder() tĩnh: chỉ bản này mang correlation id
        // và trace span sang service được gọi. Xem CorrelationPropagation của starter-web.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.baseUrl(orderingUrl).requestFactory(factory).build();
    }

    public void setOrderingUrl(String orderingUrl) {
        this.orderingUrl = orderingUrl;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
