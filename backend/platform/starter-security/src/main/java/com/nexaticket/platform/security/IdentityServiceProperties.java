// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Địa chỉ Open Host Service của identity-service.
 *
 * <p>Không đặt thì service chạy không có tra membership — hợp lệ trong test và với service thuần
 * consumer không phục vụ request người dùng.
 *
 * @param baseUrl ví dụ {@code http://identity-service:8090}
 */
@ConfigurationProperties(prefix = "nexaticket.identity")
public record IdentityServiceProperties(String baseUrl) {}
