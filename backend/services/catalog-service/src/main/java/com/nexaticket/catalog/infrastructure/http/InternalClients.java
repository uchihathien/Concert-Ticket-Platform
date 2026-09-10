// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP cho các lời gọi đọc của bảng điều khiển.
 *
 * <p><b>Timeout luôn có, và ngắn.</b> Mặc định của Java là chờ vô hạn: một service treo sẽ giữ
 * luồng của Catalog cho tới khi hết heap, và sự cố của analytics lan thành sự cố của cả catalog —
 * kể cả với đường đọc công khai không liên quan gì tới nó.
 *
 * <p>Ngân sách ở đây rộng hơn của saga checkout (2 giây) vì đây là màn hình quản trị, không phải
 * khách đang giữ chỗ; nhưng vẫn phải hữu hạn và vẫn phải nhỏ hơn kiên nhẫn của người đang nhìn màn
 * hình.
 *
 * <p><b>Không retry.</b> Hỏng thì bảng điều khiển trả phần còn lại kèm {@code degraded} — xem
 * {@code OrganizationDashboardQuery}. Thử lại chỉ nhân đôi thời gian chờ để đi tới cùng kết luận.
 */
@Configuration
@ConfigurationProperties(prefix = "nexaticket.catalog.services")
public class InternalClients {

    private String inventoryUrl = "http://localhost:8092";
    private String analyticsUrl = "http://localhost:8099";
    private Duration timeout = Duration.ofSeconds(3);

    @Bean
    public RestClient inventoryClient(RestClient.Builder builder) {
        return build(builder, inventoryUrl, timeout);
    }

    @Bean
    public RestClient analyticsClient(RestClient.Builder builder) {
        return build(builder, analyticsUrl, timeout);
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

    public void setAnalyticsUrl(String analyticsUrl) {
        this.analyticsUrl = analyticsUrl;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
