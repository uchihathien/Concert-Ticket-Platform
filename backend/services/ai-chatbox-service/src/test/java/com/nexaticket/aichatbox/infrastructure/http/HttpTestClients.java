// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import java.time.Duration;
import org.springframework.web.client.RestClient;

/**
 * Dựng {@link RestClient} giống hệt {@code InternalClients}: factory JDK có pool, hạn ngắn, không theo
 * chuyển hướng.
 *
 * <p>Không dùng {@code RestClient.builder().build()} trần: nó chọn Apache HttpClient nếu có trên
 * classpath, và Apache tự thử lại 503/429 — một hành vi mà bản chạy thật KHÔNG có, nên test sẽ đếm sai
 * số lần gọi.
 */
final class HttpTestClients {

    private HttpTestClients() {}

    static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(PooledHttpFactory.create(Duration.ofSeconds(3), Duration.ofSeconds(3)))
                .build();
    }
}
