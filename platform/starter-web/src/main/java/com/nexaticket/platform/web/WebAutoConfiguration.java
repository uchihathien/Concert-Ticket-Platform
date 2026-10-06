// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web;

import com.nexaticket.platform.web.correlation.CorrelationIdFilter;
import com.nexaticket.platform.web.correlation.CorrelationPropagation;
import com.nexaticket.platform.web.error.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
public class WebAutoConfiguration {

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    /**
     * Truyền correlation id sang lời gọi HTTP đi ra.
     *
     * <p>Chỉ có tác dụng với client dựng từ {@code RestClient.Builder} được tiêm vào — xem
     * {@link CorrelationPropagation}.
     */
    @Bean
    public CorrelationPropagation correlationPropagation() {
        return new CorrelationPropagation();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
