// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Đúng MỘT {@link LlmProviderPort} với mọi giá trị của {@code AI_PROVIDER}.
 *
 * <p>Cùng lớp lỗi với {@code EmbeddingProviderSelectionTest} (17 lần restart trên production): biến
 * môi trường có mặt nhưng RỖNG thì {@code @ConditionalOnProperty} không khớp adapter nào, và service
 * không khởi động. Ca rỗng đứng ĐẦU vì nó là ca đã xảy ra thật ở phía embedding.
 */
class LlmProviderSelectionTest {

    private static final String PROP = LlmProvider.PROPERTY;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Props.class)
            .withUserConfiguration(OllamaLlmAdapter.class, AnthropicClientConfig.class, AnthropicLlmAdapter.class);

    @Test
    void rong_thi_dung_ollama_nhu_khi_thieu_han() {
        runner.withPropertyValues(PROP + "=")
                .run(ctx -> assertThat(ctx).hasSingleBean(LlmProviderPort.class).hasSingleBean(OllamaLlmAdapter.class));
    }

    @Test
    void thieu_han_thi_dung_ollama() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(LlmProviderPort.class).hasSingleBean(OllamaLlmAdapter.class));
    }

    @Test
    void local_thi_dung_ollama() {
        runner.withPropertyValues(PROP + "=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(LlmProviderPort.class).hasSingleBean(OllamaLlmAdapter.class));
    }

    @Test
    void anthropic_thi_dung_anthropic() {
        runner.withPropertyValues(PROP + "=anthropic", "nexaticket.aichatbox.anthropic.api-key=k")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LlmProviderPort.class).hasSingleBean(AnthropicLlmAdapter.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OllamaProperties.class, AnthropicProperties.class, AgentProperties.class})
    static class Props {}
}
