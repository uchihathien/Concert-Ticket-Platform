// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    /**
     * Job dọn bản ghi cũ — chỉ bật khi service khai ít nhất một route cần khoá
     * ({@code nexaticket.idempotency.required-paths[0]}): service không dùng filter thì bảng không phình.
     * Không đòi servlet: dọn bảng là việc của bất kỳ ứng dụng nào có {@link IdempotencyStore}.
     *
     * <p>Khai ở ĐÂY, cạnh bean {@link IdempotencyStore}, chứ không trong một lớp cấu hình lồng:
     * {@code @ConditionalOnBean} trên lớp lồng được đánh giá TRƯỚC các phương thức {@code @Bean} của lớp ngoài,
     * nên nó không thấy {@link IdempotencyStore} và job bị bỏ qua trong im lặng (đã xảy ra, bắt được bằng
     * {@code OrderIdempotencyIT}).
     */
    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnProperty(prefix = "nexaticket.idempotency", name = "required-paths[0]")
    public IdempotencyCleanupJob idempotencyCleanupJob(IdempotencyStore store, IdempotencyProperties properties) {
        return new IdempotencyCleanupJob(store, properties);
    }

    /** Bật {@code @Scheduled} cho service chưa có; chỉ phụ thuộc cấu hình, không phụ thuộc bean khác. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "nexaticket.idempotency", name = "required-paths[0]")
    @EnableConfigurationProperties(IdempotencyProperties.class)
    @EnableScheduling
    static class SchedulingConfiguration {}
}
