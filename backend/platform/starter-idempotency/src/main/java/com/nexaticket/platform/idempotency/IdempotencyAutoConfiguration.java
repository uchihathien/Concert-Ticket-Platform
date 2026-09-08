// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tự cấu hình idempotency.
 *
 * <p>Xem ghi chú về {@code after} ở {@code OutboxAutoConfiguration}: thiếu nó thì
 * {@link ConditionalOnBean} chạy quá sớm và bean bị bỏ qua trong im lặng.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public IdempotencyStore idempotencyStore(JdbcTemplate jdbc) {
        return new IdempotencyStore(jdbc);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }
}
