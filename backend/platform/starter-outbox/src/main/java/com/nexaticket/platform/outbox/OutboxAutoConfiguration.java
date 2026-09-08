// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tự cấu hình outbox.
 *
 * <p>{@code after} là bắt buộc, không phải trang trí: {@link ConditionalOnBean} được đánh giá tại
 * thời điểm auto-configuration này chạy. Không khai thứ tự thì nó có thể chạy <b>trước</b>
 * {@code JdbcTemplateAutoConfiguration}, thấy chưa có {@code JdbcTemplate} nào và tự bỏ qua — hậu
 * quả là bean {@code OutboxWriter} biến mất một cách im lặng và service hỏng lúc khởi động.
 */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, RabbitAutoConfiguration.class})
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new OutboxWriter(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnBean({JdbcTemplate.class, RabbitTemplate.class})
    public OutboxPublisher outboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        return new OutboxPublisher(jdbc, rabbit);
    }
}
