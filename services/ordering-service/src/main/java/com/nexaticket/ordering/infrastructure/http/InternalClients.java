// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.http;

import com.nexaticket.ordering.application.command.OrderingProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP cho các lời gọi nội bộ của saga.
 *
 * <p><b>Timeout luôn có, và ngắn.</b> Mặc định của Java là chờ vô hạn: một service treo sẽ giữ
 * luồng của Ordering cho tới khi hết heap, và sự cố của một service lan thành sự cố toàn hệ thống.
 * 2 giây là ngân sách thời gian của người dùng đang nhìn màn hình chờ, không phải con số kỹ thuật.
 *
 * <p><b>Không retry ở tầng này.</b> Retry một lời gọi đã timeout sẽ nhân đôi thời gian chờ của
 * khách, và bên kia có thể đã làm xong việc — retry sẽ làm lần hai. Hỏng thì bù trừ ngay
 * (sagas.md §2).
 */
@Configuration
@ConfigurationProperties(prefix = "nexaticket.ordering.services")
public class InternalClients {

    private String inventoryUrl = "http://localhost:8092";
    private String catalogUrl = "http://localhost:8091";
    private String paymentUrl = "http://localhost:8095";

    @Bean
    public RestClient inventoryClient(OrderingProperties properties, RestClient.Builder builder) {
        return build(builder, inventoryUrl, properties.remoteTimeout());
    }

    @Bean
    public RestClient catalogClient(OrderingProperties properties, RestClient.Builder builder) {
        return build(builder, catalogUrl, properties.remoteTimeout());
    }

    @Bean
    public RestClient paymentClient(OrderingProperties properties, RestClient.Builder builder) {
        return build(builder, paymentUrl, properties.remoteTimeout());
    }

    // Builder ĐƯỢC TIÊM, không phải RestClient.builder() tĩnh: chỉ bản này mang theo correlation
    // id và trace span sang service được gọi. Xem CorrelationPropagation.
    private static RestClient build(RestClient.Builder builder, String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void setInventoryUrl(String inventoryUrl) {
        this.inventoryUrl = inventoryUrl;
    }

    public void setCatalogUrl(String catalogUrl) {
        this.catalogUrl = catalogUrl;
    }

    public void setPaymentUrl(String paymentUrl) {
        this.paymentUrl = paymentUrl;
    }
}
