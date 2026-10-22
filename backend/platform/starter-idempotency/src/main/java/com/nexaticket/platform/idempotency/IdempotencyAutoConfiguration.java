// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    /**
     * Bộ lọc Idempotency-Key — chỉ cho ứng dụng servlet có {@code starter-security} (cần
     * {@code TenantContext}). Chạy SAU chuỗi bảo mật (order 0 > -100) để biết người dùng là ai.
     * Không khai {@code nexaticket.idempotency.required-paths} thì bộ lọc không áp cho route nào.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "com.nexaticket.platform.security.tenant.TenantContext")
    @EnableConfigurationProperties(IdempotencyProperties.class)
    static class FilterConfiguration {

        @Bean
        FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
                IdempotencyProperties properties,
                ObjectProvider<IdempotencyStore> store,
                ObjectProvider<ObjectMapper> json) {
            FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(
                    properties, Optional.ofNullable(store.getIfAvailable()), json.getIfAvailable(ObjectMapper::new)));
            registration.setOrder(0);
            return registration;
        }
    }
}
