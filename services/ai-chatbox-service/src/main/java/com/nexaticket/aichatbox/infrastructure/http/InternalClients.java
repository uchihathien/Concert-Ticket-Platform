// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    private String catalogUrl = "http://localhost:8091";
    private String identityUrl = "http://localhost:8090";
    private Duration timeout = Duration.ofSeconds(3);

    @Bean
    public RestClient orderingClient(RestClient.Builder builder) {
        // Builder ĐƯỢC TIÊM, không phải RestClient.builder() tĩnh: chỉ bản này mang correlation id
        // và trace span sang service được gọi. Xem CorrelationPropagation của starter-web.
        return builder.baseUrl(orderingUrl)
                .requestFactory(PooledHttpFactory.create(timeout, timeout))
                .build();
    }

    /**
     * Client tra danh mục sự kiện.
     *
     * <p>Dùng chung hạn 3 giây với ordering, và lý do ở đầu lớp này vẫn đúng: lời gọi nằm bên trong
     * một lượt chat mà khách đang chờ. Catalog đọc từ read model nên nhanh hơn hẳn ngân sách ấy —
     * quá hạn ở đây nghĩa là catalog đang có sự cố, và lúc đó chờ thêm cũng không cứu được lượt chat.
     */
    @Bean
    public RestClient catalogClient(RestClient.Builder builder) {
        return builder.baseUrl(catalogUrl)
                .requestFactory(PooledHttpFactory.create(timeout, timeout))
                .build();
    }

    /**
     * Client tra tên người trực.
     *
     * <p>Hạn dùng chung 3 giây vẫn đúng ở đây, nhưng vì lý do khác: lời gọi này nằm trong request mở
     * hàng đợi hỗ trợ. Quá hạn thì phiếu hiện không có tên — xem {@code IdentityLookupPort} — chứ
     * không làm cả màn hình hỏng.
     */
    @Bean
    public RestClient identityClient(RestClient.Builder builder) {
        return builder.baseUrl(identityUrl)
                .requestFactory(PooledHttpFactory.create(timeout, timeout))
                .build();
    }

    public void setOrderingUrl(String orderingUrl) {
        this.orderingUrl = orderingUrl;
    }

    public void setIdentityUrl(String identityUrl) {
        this.identityUrl = identityUrl;
    }

    public void setCatalogUrl(String catalogUrl) {
        this.catalogUrl = catalogUrl;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
