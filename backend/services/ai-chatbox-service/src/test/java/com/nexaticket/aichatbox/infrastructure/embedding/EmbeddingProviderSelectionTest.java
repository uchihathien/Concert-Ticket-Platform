// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import com.nexaticket.aichatbox.infrastructure.llm.OllamaProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Đúng MỘT bean {@link EmbeddingPort} được dựng, với mọi giá trị của {@code EMBEDDING_PROVIDER}.
 *
 * <h2>Vì sao test này tồn tại</h2>
 *
 * <p>Production đã chết 17 lần restart vì KHÔNG bean nào được dựng. {@code prod.yml} truyền
 * {@code EMBEDDING_PROVIDER: ${EMBEDDING_PROVIDER:-}} nên biến CÓ mặt nhưng RỖNG, và
 * {@code @ConditionalOnProperty} không phân biệt được "rỗng" với "thiếu": {@code matchIfMissing}
 * không áp dụng (property có mặt) và {@code havingValue} không khớp (""≠"local"). Cả ba adapter cùng
 * vắng mặt, hai use case nhận {@code EmbeddingPort} qua hàm dựng, và service không khởi động nổi với
 * {@code No qualifying bean of type EmbeddingPort}.
 *
 * <p>93 unit test của module đều xanh suốt lúc đó — chúng kiểm hành vi, không kiểm việc Spring có
 * dựng nổi context hay không. Đây là lớp lỗi duy nhất mà {@code ApplicationContextRunner} bắt được,
 * và nó chạy trong vài chục mili giây, không cần database hay container nào.
 *
 * <p>Trường hợp chuỗi rỗng là trường hợp ĐẦU TIÊN trong danh sách dưới đây, không phải trường hợp
 * cuối: nó là cái đã xảy ra thật.
 */
class EmbeddingProviderSelectionTest {

    private static final String PROP = "nexaticket.aichatbox.embedding.provider";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Stubs.class)
            .withUserConfiguration(
                    OllamaEmbeddingAdapter.class, VoyageEmbeddingAdapter.class, DisabledEmbeddingAdapter.class);

    @Test
    void rong_thi_dung_ollama_nhu_khi_thieu_han() {
        runner.withPropertyValues(PROP + "=")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(EmbeddingPort.class).hasSingleBean(OllamaEmbeddingAdapter.class));
    }

    @Test
    void thieu_han_thi_dung_ollama() {
        runner.run(
                ctx -> assertThat(ctx).hasSingleBean(EmbeddingPort.class).hasSingleBean(OllamaEmbeddingAdapter.class));
    }

    @Test
    void local_thi_dung_ollama() {
        runner.withPropertyValues(PROP + "=local")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(EmbeddingPort.class).hasSingleBean(OllamaEmbeddingAdapter.class));
    }

    @Test
    void anthropic_thi_dung_voyage() {
        runner.withPropertyValues(PROP + "=anthropic", "nexaticket.aichatbox.embedding.api-key=k")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(EmbeddingPort.class).hasSingleBean(VoyageEmbeddingAdapter.class));
    }

    @Test
    void none_thi_tat_nhung_VAN_co_mot_bean() {
        // Không bean nào là thứ đã làm service chết; `none` phải là một bean ném lỗi, không phải khoảng trống.
        runner.withPropertyValues(PROP + "=none").run(ctx -> assertThat(ctx)
                .hasSingleBean(EmbeddingPort.class)
                .hasSingleBean(DisabledEmbeddingAdapter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        OllamaProperties ollamaProperties() {
            return new OllamaProperties(
                    "http://localhost:11434",
                    "qwen2.5:7b-instruct",
                    "bge-m3",
                    1024,
                    Duration.ofSeconds(120),
                    Duration.ofSeconds(60),
                    1024);
        }
    }
}
