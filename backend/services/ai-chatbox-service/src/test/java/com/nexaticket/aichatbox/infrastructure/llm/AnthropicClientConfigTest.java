// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Số lần thử lại của SDK là con số của ta, không phải mặc định 2 của SDK. */
class AnthropicClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Props.class, AnthropicClientConfig.class)
            .withPropertyValues(LlmProvider.PROPERTY + "=anthropic", "nexaticket.aichatbox.anthropic.api-key=k");

    @Test
    void mac_dinh_thu_lai_mot_lan() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AnthropicClient.class);
            assertThat(ctx.getBean(AnthropicProperties.class).maxRetries()).isEqualTo(1);
        });
    }

    @Test
    void khai_khong_thi_khong_thu_lai() {
        runner.withPropertyValues("nexaticket.aichatbox.anthropic.max-retries=0")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AnthropicClient.class);
                    assertThat(ctx.getBean(AnthropicProperties.class).maxRetries())
                            .isZero();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AnthropicProperties.class)
    static class Props {}
}
