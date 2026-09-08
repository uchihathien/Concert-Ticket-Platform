// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Khoá rate limit: người dùng đã đăng nhập tính theo danh tính, khách vãng lai tính theo IP.
 *
 * <p>Nếu chỉ tính theo IP thì cả một văn phòng sau NAT dùng chung hạn mức; nếu chỉ tính theo user
 * thì luồng công khai không được bảo vệ.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver principalOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    var remote = exchange.getRequest().getRemoteAddress();
                    return "ip:"
                            + (remote == null ? "unknown" : remote.getAddress().getHostAddress());
                }));
    }
}
