// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
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

    /**
     * Số proxy tin cậy đứng trước gateway.
     *
     * <p>Đây là con số phải khớp với hạ tầng thật, không phải một tuỳ chọn thẩm mỹ — và sai theo cả
     * hai hướng đều hỏng:
     *
     * <ul>
     *   <li><b>Quá nhỏ</b> (hoặc không dùng {@code X-Forwarded-For}): mọi khách vãng lai mang địa
     *       chỉ của load balancer, nên cả internet dùng chung MỘT hạn mức 50 rps. Một người dò tự
     *       động làm cả sàn vé bị chặn.
     *   <li><b>Quá lớn</b>: người gọi tự bịa thêm địa chỉ vào đầu {@code X-Forwarded-For} và có hạn
     *       mức mới sau mỗi request — tức là không còn hạn mức nào.
     * </ul>
     *
     * <p>Mặc định 1 = đúng một proxy (ingress) đứng trước. Sau CDN thì thường là 2.
     */
    @Value("${nexaticket.gateway.trusted-proxy-count:1}")
    private int trustedProxyCount;

    @Bean
    public KeyResolver principalOrIpKeyResolver() {
        XForwardedRemoteAddressResolver addresses = XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyCount);

        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // `resolve` đọc X-Forwarded-For nhưng CHỈ tin `trustedProxyCount` mục cuối —
                    // phần do proxy của ta ghi. Mọi mục người gọi tự thêm vào đầu bị bỏ qua.
                    var remote = addresses.resolve(exchange);
                    return "ip:"
                            + (remote == null ? "unknown" : remote.getAddress().getHostAddress());
                }));
    }
}
