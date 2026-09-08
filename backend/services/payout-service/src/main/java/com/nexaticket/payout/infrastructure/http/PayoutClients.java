// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.infrastructure.http;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client cho hai service mà payout đọc dữ liệu.
 *
 * <p>Timeout rộng hơn saga checkout (5 giây thay vì 2): ở đây không có khách nào đang nhìn màn
 * hình chờ, và người vận hành thà đợi thêm ba giây còn hơn nhận một lỗi timeout rồi phải bấm lại.
 */
@Configuration
public class PayoutClients {

    @Bean
    public RestClient ledgerClient(
            @Value("${nexaticket.payout.services.ledger-url:http://localhost:8094}") String url) {
        return build(url);
    }

    @Bean
    public RestClient identityClient(
            @Value("${nexaticket.payout.services.identity-url:http://localhost:8090}") String url) {
        return build(url);
    }

    private static RestClient build(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
