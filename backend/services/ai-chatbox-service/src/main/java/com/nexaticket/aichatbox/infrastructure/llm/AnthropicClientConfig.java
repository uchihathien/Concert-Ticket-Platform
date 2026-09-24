// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dựng client của SDK Anthropic. */
@ConditionalOnProperty(name = LlmProvider.PROPERTY, havingValue = "anthropic")
@Configuration
public class AnthropicClientConfig {

    /**
     * <p><b>Hạn phải khai tường minh.</b> Mặc định của SDK là 10 phút. Với một người đang nhìn màn
     * hình chat thì 10 phút không khác gì treo, và tệ hơn: mỗi request treo giữ một luồng của
     * Tomcat, nên một sự cố phía nhà cung cấp đủ sức làm chết cả service bằng cách chiếm hết luồng.
     */
    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("Thiếu nexaticket.aichatbox.anthropic.api-key (biến ANTHROPIC_API_KEY)");
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .build();
    }
}
